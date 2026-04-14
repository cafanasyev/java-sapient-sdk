package io.sapient.transmission;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Timestamp;
import com.google.protobuf.util.JsonFormat;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.sapient.transport.ConnectionState;
import io.sapient.transport.IClient;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
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
 *
 * <p>The underlying {@link IClient} is started when nodes come online and closed when the last node
 * goes offline, in accordance with the SAPIENT protocol requirement that a Registration message
 * must be sent shortly after connection.
 */
@Slf4j
public class NodeDispatcher implements INodeDispatcher {

    @NonNull private final IClient client;
    @NonNull private final NodeDispatcherConfig config;

    final ConcurrentMap<UUID, NodeWrapper> onlineNodes = new ConcurrentHashMap<>();
    final ConcurrentMap<UUID, NodeWrapper> offlineNodes = new ConcurrentHashMap<>();

    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final AtomicBoolean clientRunning = new AtomicBoolean(false);

    // fraction applied to period thresholds so scheduler jitter and GC pauses of a few
    // milliseconds do not mask a real outage at the boundary
    static final double THRESHOLD_TOLERANCE = 0.95;

    // set when the client transitions to DISCONNECTED
    private final AtomicReference<Instant> disconnectedAt = new AtomicReference<>();
    // incremented each time the client reconnects after a gap exceeding the grace period;
    // nodes compare against the epoch they captured at registration to detect server-side purge
    private final AtomicLong reregistrationEpoch = new AtomicLong(0);

    static Duration withTolerance(Duration d) {
        return Duration.ofNanos((long) (d.toNanos() * THRESHOLD_TOLERANCE));
    }

    /**
     * Creates a dispatcher backed by the given client and configuration.
     *
     * @param client the transport client used to send and receive messages
     * @param config dispatcher configuration (polling intervals, timeouts)
     */
    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP2",
            justification =
                    "IClient is an injected interface dependency, not a mutable data structure — defensive copy is not applicable")
    public NodeDispatcher(@NonNull IClient client, @NonNull NodeDispatcherConfig config) {
        this.client = client;
        this.config = config;
        client.subscribe(this::onMessage);
        client.addStateChangeListener(this::onClientStateChange);
    }

    private void onClientStateChange(ConnectionState newState, Instant ts) {
        if (newState == ConnectionState.DISCONNECTED) {
            disconnectedAt.set(ts);
        } else if (newState == ConnectionState.CONNECTED) {
            Instant lost = disconnectedAt.getAndSet(null);
            if (lost == null) return;
            Duration gap = Duration.between(lost, ts);
            if (gap.compareTo(withTolerance(config.reconnectGracePeriod())) >= 0) {
                reregistrationEpoch.incrementAndGet();
            }
        }
    }

    /**
     * Returns the current re-registration epoch. Increments on each grace-period-exceeding
     * reconnect.
     */
    long reregistrationEpoch() {
        return reregistrationEpoch.get();
    }

    private void onMessage(SapientMessage message) {
        try {
            _onMessage(message);
        } catch (IllegalArgumentException e) {
            log.error("invalid field in incoming message", e);
        } catch (TimeoutException e) {
            log.error("publish timeout while processing incoming message", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void _onMessage(SapientMessage message) throws TimeoutException, InterruptedException {
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
                Task task = message.getTask();
                String reason = null;
                if (node == null) {
                    reason = "node not registered: " + destinationId;
                } else if (!onlineNodes.containsKey(destinationId)) {
                    reason = "node offline";
                }
                if (reason != null) {
                    log.warn(
                            "rejecting task {} for node {}: {}",
                            taskSummary(task),
                            destinationId,
                            reason);
                    rejectTask(task, destinationId, reason);
                } else {
                    log.info("delivering task {} to node: {}", taskSummary(task), destinationId);
                    handleTask(task, node);
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

    private static String taskSummary(Task task) {
        return "%s [command=%s, request=%s]"
                .formatted(
                        task.getTaskId(),
                        task.getCommand().getCommandCase(),
                        task.getCommand().getRequest());
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
            if (!onlineNodes.isEmpty() && clientRunning.compareAndSet(false, true)) {
                client.start();
            }
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
                    clientRunning.set(false);
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
    public SapientMessage publish(Registration registration, UUID nodeId, Duration timeout)
            throws TimeoutException, InterruptedException {
        return publish(SapientMessage.newBuilder().setRegistration(registration), nodeId, timeout);
    }

    @Override
    public SapientMessage publish(StatusReport status, UUID nodeId, Duration timeout)
            throws TimeoutException, InterruptedException {
        NodeWrapper node = findNode(nodeId);
        if (node != null && status.getInfo() == StatusReport.Info.INFO_NEW) {
            StatusReport prev = node.getLastStatusReport().getAndSet(status);
            if (prev != null && clearInfo(prev).equals(clearInfo(status))) {
                status = status.toBuilder().setInfo(StatusReport.Info.INFO_UNCHANGED).build();
            }
        }
        return publish(SapientMessage.newBuilder().setStatusReport(status), nodeId, timeout);
    }

    private static StatusReport clearInfo(StatusReport status) {
        return status.toBuilder().clearInfo().build();
    }

    @Override
    public SapientMessage publish(TaskAck taskAck, UUID nodeId, Duration timeout)
            throws TimeoutException, InterruptedException {
        return publish(SapientMessage.newBuilder().setTaskAck(taskAck), nodeId, timeout);
    }

    @Override
    public SapientMessage publish(Alert alert, UUID nodeId, Duration timeout)
            throws TimeoutException, InterruptedException {
        return publish(SapientMessage.newBuilder().setAlert(alert), nodeId, timeout);
    }

    @Override
    public SapientMessage publish(DetectionReport detection, UUID nodeId, Duration timeout)
            throws TimeoutException, InterruptedException {
        return publish(SapientMessage.newBuilder().setDetectionReport(detection), nodeId, timeout);
    }

    private SapientMessage publish(SapientMessage.Builder builder, UUID nodeId, Duration timeout)
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
        client.publish(message, timeout);
        return message;
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
}
