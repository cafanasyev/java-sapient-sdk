package io.sapient.transmission;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Timestamp;
import com.google.protobuf.util.JsonFormat;
import io.sapient.transport.IClient;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import uk.gov.dstl.sapientmsg.bsiflex335v2.Alert;
import uk.gov.dstl.sapientmsg.bsiflex335v2.DetectionReport;
import uk.gov.dstl.sapientmsg.bsiflex335v2.Registration;
import uk.gov.dstl.sapientmsg.bsiflex335v2.SapientMessage;
import uk.gov.dstl.sapientmsg.bsiflex335v2.StatusReport;
import uk.gov.dstl.sapientmsg.bsiflex335v2.Task;
import uk.gov.dstl.sapientmsg.bsiflex335v2.TaskAck;

/**
 * NodeDispatcher connects to a fusion node, registers all provided nodes and sends StatusReports
 * based on time interval specified by each edge/fusion node in its Registration message.
 *
 * <p>Each registered node is managed by its own virtual thread that handles the full lifecycle:
 * online polling → registration → wait for ack → status reporting → goodbye on offline. Publish of
 * SapientMessages can also be initiated externally (not by the node thread).
 */
@Slf4j
public class NodeDispatcher implements INodeDispatcher {

    @NonNull private final IClient client;
    @NonNull private final NodeDispatcherConfig config;

    final ConcurrentMap<UUID, NodeWrapper> onlineNodes = new ConcurrentHashMap<>();
    final ConcurrentMap<UUID, NodeWrapper> offlineNodes = new ConcurrentHashMap<>();

    private volatile boolean running = true;
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final Condition nodeOnline = rwLock.writeLock().newCondition();

    /**
     * Creates a dispatcher backed by the given client and configuration.
     *
     * @param client the transport client used to send and receive messages
     * @param config dispatcher configuration (polling intervals, timeouts)
     */
    public NodeDispatcher(@NonNull IClient client, @NonNull NodeDispatcherConfig config) {
        this.client = client;
        this.config = config;
        client.subscribe(this::onMessage);
    }

    private void onMessage(ByteBuffer buffer) {
        try {
            _onMessage(buffer);
        } catch (InvalidProtocolBufferException e) {
            log.error("failed to parse incoming message", e);
        } catch (IllegalArgumentException e) {
            log.error("invalid field in incoming message", e);
        } catch (TimeoutException e) {
            log.error("publish timeout while processing incoming message", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void _onMessage(ByteBuffer buffer)
            throws InvalidProtocolBufferException, TimeoutException, InterruptedException {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        SapientMessage message = SapientMessage.parseFrom(bytes);
        UUID destinationId = UUID.fromString(message.getDestinationId());
        log.info("received {} for node: {}", message.getContentCase(), destinationId);
        NodeWrapper node = findNode(destinationId);
        switch (message.getContentCase()) {
            case REGISTRATION_ACK -> {
                if (node == null) {
                    log.error("no node registered for destination: {}", destinationId);
                    return;
                }
                if (!node.getAckQueue().offer(message.getRegistrationAck())) {
                    log.error("ack queue full, dropping ack for node: {}", destinationId);
                }
            }
            case ALERT_ACK -> {
                if (node == null) {
                    log.error("no node registered for destination: {}", destinationId);
                    return;
                }
                node.getNode().onAlertAck(message.getAlertAck());
            }
            case TASK -> {
                if (node == null) {
                    var reason = "node not registered: " + destinationId;
                    log.warn(
                            "rejecting task {} [command={}, request={}] for node {}: {}",
                            message.getTask().getTaskId(),
                            message.getTask().getCommand().getCommandCase(),
                            message.getTask().getCommand().getRequest(),
                            destinationId,
                            reason);
                    rejectTask(message.getTask(), destinationId, reason);
                } else if (!onlineNodes.containsKey(destinationId)) {
                    var reason = "node offline";
                    log.warn(
                            "rejecting task {} [command={}, request={}] for node {}: {}",
                            message.getTask().getTaskId(),
                            message.getTask().getCommand().getCommandCase(),
                            message.getTask().getCommand().getRequest(),
                            destinationId,
                            reason);
                    rejectTask(message.getTask(), destinationId, reason);
                } else {
                    log.info(
                            "delivering task {} to node: {}",
                            message.getTask().getTaskId(),
                            destinationId);
                    handleTask(message.getTask(), node);
                }
            }
            default -> {}
        }
    }

    private void handleTask(Task task, NodeWrapper nodeWrapper)
            throws TimeoutException, InterruptedException {
        INode node = nodeWrapper.getNode();

        if (isRegistrationRequest(task)) {
            publish(node.getRegistration(), node.getNodeId(), config.publishTimeout());
            return;
        }

        node.onTask(task);
    }

    private void rejectTask(Task task, UUID nodeId, String reason)
            throws TimeoutException, InterruptedException {
        TaskAck reject =
                TaskAck.newBuilder()
                        .setTaskId(task.getTaskId())
                        .setTaskStatus(TaskAck.TaskStatus.TASK_STATUS_REJECTED)
                        .addReason(reason)
                        .build();
        publish(reject, nodeId, config.publishTimeout());
    }

    private static boolean isRegistrationRequest(Task task) {
        return task.hasCommand()
                && task.getCommand().getCommandCase() == Task.Command.CommandCase.REQUEST
                && "registration".equalsIgnoreCase(task.getCommand().getRequest());
    }

    NodeWrapper findNode(UUID id) {
        rwLock.readLock().lock();
        try {
            NodeWrapper w = onlineNodes.get(id);
            return w != null ? w : offlineNodes.get(id);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    void onNodeOnline(UUID id) {
        rwLock.writeLock().lock();
        try {
            NodeWrapper w = offlineNodes.remove(id);
            if (w != null) onlineNodes.put(id, w);
            nodeOnline.signalAll();
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    void onNodeOffline(UUID id) {
        rwLock.writeLock().lock();
        try {
            NodeWrapper w = onlineNodes.remove(id);
            if (w != null) offlineNodes.put(id, w);
            if (onlineNodes.isEmpty()) {
                try {
                    client.close();
                } catch (Exception e) {
                    log.error("failed to close client", e);
                }
            }
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    @Override
    public void register(INode node) {
        log.info("registering the node: {}", node.getNodeId());
        offlineNodes.computeIfAbsent(node.getNodeId(), k -> new NodeWrapper(node, this, config));
    }

    @Override
    public void unregister(INode node) {
        log.info("unregistering the node: {}", node.getNodeId());
        UUID id = node.getNodeId();
        NodeWrapper wrapper = offlineNodes.remove(id);
        if (wrapper == null) wrapper = onlineNodes.remove(id);
        if (wrapper == null) return;
        wrapper.close();
    }

    @Override
    public void publish(Registration registration, UUID nodeId, Duration timeout)
            throws TimeoutException, InterruptedException {
        publish(SapientMessage.newBuilder().setRegistration(registration), nodeId, timeout);
    }

    @Override
    public void publish(StatusReport status, UUID nodeId, Duration timeout)
            throws TimeoutException, InterruptedException {
        NodeWrapper node = findNode(nodeId);
        StatusReport.Info info = StatusReport.Info.INFO_NEW;
        if (node != null) {
            StatusReport prev = node.getLastStatusReport().getAndSet(status);
            if (prev != null && clearInfo(prev).equals(clearInfo(status))) {
                info = StatusReport.Info.INFO_UNCHANGED;
            }
        }
        StatusReport withInfo = status.toBuilder().setInfo(info).build();
        publish(SapientMessage.newBuilder().setStatusReport(withInfo), nodeId, timeout);
    }

    private static StatusReport clearInfo(StatusReport status) {
        return status.toBuilder().clearInfo().build();
    }

    @Override
    public void publish(TaskAck taskAck, UUID nodeId, Duration timeout)
            throws TimeoutException, InterruptedException {
        publish(SapientMessage.newBuilder().setTaskAck(taskAck), nodeId, timeout);
    }

    @Override
    public void publish(Alert alert, UUID nodeId, Duration timeout)
            throws TimeoutException, InterruptedException {
        publish(SapientMessage.newBuilder().setAlert(alert), nodeId, timeout);
    }

    @Override
    public void publish(DetectionReport detection, UUID nodeId, Duration timeout)
            throws TimeoutException, InterruptedException {
        publish(SapientMessage.newBuilder().setDetectionReport(detection), nodeId, timeout);
    }

    @Override
    public void goodbye(UUID nodeId, Duration timeout)
            throws TimeoutException, InterruptedException {
        publish(SapientMessage.newBuilder(), nodeId, timeout);
    }

    private void publish(SapientMessage.Builder builder, UUID nodeId, Duration timeout)
            throws TimeoutException, InterruptedException {
        builder.setNodeId(nodeId.toString())
                .setTimestamp(timestampNow())
                .setDestinationId(config.destinationId().toString());
        SapientMessage message = builder.build();
        log.info(
                "sending {} nodeId={} destinationId={}",
                message.getContentCase(),
                nodeId,
                config.destinationId());
        if (log.isDebugEnabled()) {
            try {
                log.debug("message: {}", JsonFormat.printer().print(message));
            } catch (InvalidProtocolBufferException e) {
                log.debug("message: <serialization failed>", e);
            }
        }
        client.publish(ByteBuffer.wrap(message.toByteArray()), timeout);
    }

    private static Timestamp timestampNow() {
        Instant now = Instant.now();
        return Timestamp.newBuilder()
                .setSeconds(now.getEpochSecond())
                .setNanos(now.getNano())
                .build();
    }

    @Override
    public void close() {
        log.info("stopping the dispatcher gracefully");
        running = false;
        rwLock.writeLock().lock();
        try {
            nodeOnline.signalAll();
        } finally {
            rwLock.writeLock().unlock();
        }
        onlineNodes.values().forEach(NodeWrapper::close);
        offlineNodes.values().forEach(NodeWrapper::close);
        onlineNodes.clear();
        offlineNodes.clear();
        try {
            client.close();
        } catch (Exception e) {
            log.error("failed to close client", e);
        }
        log.info("dispatcher stopped");
    }

    @Override
    public void run() {
        while (running) {
            rwLock.writeLock().lock();
            try {
                while (running && onlineNodes.isEmpty()) {
                    try {
                        nodeOnline.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            } finally {
                rwLock.writeLock().unlock();
            }
            if (!running) break;
            client.run();
        }
    }
}
