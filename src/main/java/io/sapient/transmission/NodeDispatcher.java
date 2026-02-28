package io.sapient.transmission;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Timestamp;
import io.sapient.transport.IClient;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeoutException;
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

    final ConcurrentMap<UUID, NodeWrapper> nodes = new ConcurrentHashMap<>();

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
        NodeWrapper node = nodes.get(destinationId);
        UUID senderId = message.getNodeId().isEmpty() ? null : UUID.fromString(message.getNodeId());
        switch (message.getContentCase()) {
            case REGISTRATION_ACK -> {
                if (node == null) {
                    log.error("no node registered for destination: {}", destinationId);
                    return;
                }
                if (message.getRegistrationAck().getAcceptance()) {
                    node.getFusionNodeId().set(UUID.fromString(message.getNodeId()));
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
            case TASK -> handleTask(message.getTask(), destinationId, senderId, node);
            default -> {}
        }
    }

    private void handleTask(Task task, UUID nodeId, UUID senderId, NodeWrapper nodeWrapper)
            throws TimeoutException, InterruptedException {
        if (nodeWrapper == null) {
            rejectTask(task, nodeId, senderId, "node not registered: " + nodeId);
            return;
        }

        INode node = nodeWrapper.getNode();

        if (isRegistrationRequest(task)) {
            if (node.isOnline()) {
                publish(
                        node.getRegistration(),
                        node.getNodeId(),
                        nodeWrapper.getFusionNodeId().get(),
                        config.publishTimeout());
            } else {
                rejectTask(task, nodeId, senderId, "node offline");
            }
            return;
        }

        node.onTask(task);
    }

    private void rejectTask(Task task, UUID nodeId, UUID destinationId, String reason)
            throws TimeoutException, InterruptedException {
        log.warn(
                "rejecting task {} [command={}, request={}] for node {}: {}",
                task.getTaskId(),
                task.getCommand().getCommandCase(),
                task.getCommand().getRequest(),
                nodeId,
                reason);
        TaskAck reject =
                TaskAck.newBuilder()
                        .setTaskId(task.getTaskId())
                        .setTaskStatus(TaskAck.TaskStatus.TASK_STATUS_REJECTED)
                        .addReason(reason)
                        .build();
        publish(reject, nodeId, destinationId, config.publishTimeout());
    }

    private static boolean isRegistrationRequest(Task task) {
        return task.hasCommand()
                && task.getCommand().getCommandCase() == Task.Command.CommandCase.REQUEST
                && "registration".equalsIgnoreCase(task.getCommand().getRequest());
    }

    @Override
    public void register(INode node) {
        log.info("registering the node: {}", node.getNodeId());
        nodes.computeIfAbsent(node.getNodeId(), k -> new NodeWrapper(node, this, config));
    }

    @Override
    public void unregister(INode node) {
        log.info("unregistering the node: {}", node.getNodeId());

        NodeWrapper wrapper = nodes.remove(node.getNodeId());
        if (wrapper == null) return;

        wrapper.close();
        if (!wrapper.getRegistered().getAndSet(false)) return;

        try {
            log.info("sending goodbye for the node: {}", node.getNodeId());
            goodbye(node.getNodeId(), wrapper.getFusionNodeId().get(), config.publishTimeout());
        } catch (TimeoutException | InterruptedException e) {
            log.error("failed to send goodbye for the node: {}", node.getNodeId(), e);
        }
    }

    @Override
    public void publish(
            Registration registration, UUID nodeId, UUID destinationId, Duration timeout)
            throws TimeoutException, InterruptedException {
        publish(
                SapientMessage.newBuilder().setRegistration(registration),
                nodeId,
                destinationId,
                timeout);
    }

    @Override
    public void publish(StatusReport status, UUID nodeId, UUID destinationId, Duration timeout)
            throws TimeoutException, InterruptedException {
        NodeWrapper node = nodes.get(nodeId);
        StatusReport.Info info = StatusReport.Info.INFO_NEW;
        if (node != null) {
            StatusReport prev = node.getLastStatusReport().getAndSet(status);
            if (prev != null && clearInfo(prev).equals(clearInfo(status))) {
                info = StatusReport.Info.INFO_UNCHANGED;
            }
        }
        StatusReport withInfo = status.toBuilder().setInfo(info).build();
        publish(
                SapientMessage.newBuilder().setStatusReport(withInfo),
                nodeId,
                destinationId,
                timeout);
    }

    private static StatusReport clearInfo(StatusReport status) {
        return status.toBuilder().clearInfo().build();
    }

    @Override
    public void publish(TaskAck taskAck, UUID nodeId, UUID destinationId, Duration timeout)
            throws TimeoutException, InterruptedException {
        publish(SapientMessage.newBuilder().setTaskAck(taskAck), nodeId, destinationId, timeout);
    }

    @Override
    public void publish(Alert alert, UUID nodeId, UUID destinationId, Duration timeout)
            throws TimeoutException, InterruptedException {
        publish(SapientMessage.newBuilder().setAlert(alert), nodeId, destinationId, timeout);
    }

    @Override
    public void publish(
            DetectionReport detection, UUID nodeId, UUID destinationId, Duration timeout)
            throws TimeoutException, InterruptedException {
        publish(
                SapientMessage.newBuilder().setDetectionReport(detection),
                nodeId,
                destinationId,
                timeout);
    }

    @Override
    public void goodbye(UUID nodeId, UUID destinationId, Duration timeout)
            throws TimeoutException, InterruptedException {
        publish(SapientMessage.newBuilder(), nodeId, destinationId, timeout);
    }

    private void publish(
            SapientMessage.Builder builder, UUID nodeId, UUID destinationId, Duration timeout)
            throws TimeoutException, InterruptedException {
        builder.setNodeId(nodeId.toString()).setTimestamp(timestampNow());
        if (destinationId != null) {
            builder.setDestinationId(destinationId.toString());
        }
        client.publish(ByteBuffer.wrap(builder.build().toByteArray()), timeout);
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
        nodes.values().forEach(NodeWrapper::close);
        nodes.clear();
        try {
            client.close();
        } catch (Exception e) {
            log.error("failed to close client", e);
        }
    }

    @Override
    public void run() {
        client.run();
    }
}
