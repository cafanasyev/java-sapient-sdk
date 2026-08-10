package io.sapient.transmission;

import com.github.f4b6a3.ulid.UlidCreator;
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
import org.slf4j.Logger;
import uk.gov.dstl.sapientmsg.bsiflex335v2.Alert;
import uk.gov.dstl.sapientmsg.bsiflex335v2.DetectionReport;
import uk.gov.dstl.sapientmsg.bsiflex335v2.Registration;
import uk.gov.dstl.sapientmsg.bsiflex335v2.RegistrationAck;
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
    private final AtomicBoolean closing = new AtomicBoolean(false);

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
            // capture only the first DISCONNECTED of an outage. Subsequent DISCONNECTED
            // events emitted from failed reconnect cycles must not overwrite it —
            // otherwise the measured gap shrinks to the latest retry window and the
            // grace-period check never fires for long outages.
            disconnectedAt.compareAndSet(null, ts);
        } else if (newState == ConnectionState.CONNECTED) {
            Instant lost = disconnectedAt.getAndSet(null);
            if (lost == null) return;
            Duration disconnected =
                    Duration.between(lost, ts).plus(config.connectionLossDetectionDelay());
            log.info("disconnect duration {}", disconnected);
            if (disconnected.compareTo(withTolerance(config.reconnectGracePeriod())) >= 0) {
                reregistrationEpoch.incrementAndGet();
                log.warn("disconnected longer than {}", config.reconnectGracePeriod());
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
        if (log.isDebugEnabled()) {
            logBody(log, message);
        }
        NodeWrapper node = findNode(destinationId);
        switch (message.getContentCase()) {
            case REGISTRATION_ACK -> {
                if (node == null) {
                    log.error("no node registered for destination: {}", destinationId);
                    return;
                }
                RegistrationAck ack = message.getRegistrationAck();
                // always deliver to the node — the registration may have been triggered by a
                // Task from the fusion node while the wrapper was in the status-report phase,
                // not by NodeWrapper.register(). See CHANGELOG.md §6.
                node.getNode().onRegistrationAck(ack);
                if (node.getRegistered().get()) {
                    // wrapper is in status-report phase; nothing is polling the queue.
                    // a rejected re-registration must drop the wrapper back into register().
                    if (!ack.getAcceptance()) {
                        node.getRegistered().set(false);
                    }
                } else if (!node.getAckQueue().offer(ack)) {
                    log.error("ack queue full, dropping ack signal for node: {}", destinationId);
                }
            }
            case ALERT_ACK -> {
                if (node == null) {
                    log.error("no node registered for destination: {}", destinationId);
                    return;
                }
                node.getNode().onAlertAck(message.getAlertAck());
            }
            case ERROR -> {
                if (node == null) {
                    log.error("no node registered for destination: {}", destinationId);
                    return;
                }
                node.getNode().onError(message.getError());
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
            // during shutdown the client is closed by close() itself, once every node has
            // said goodbye — a node dropping out here must not pull it out from under them
            if (onlineNodes.isEmpty() && !closing.get()) {
                closeClientIfNeeded();
            }
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * Closes the underlying client, at most once per {@link #onNodeOnline} start. Both the node
     * threads and {@link #close()} reach this point, so the flag is what keeps a shutdown with
     * several nodes from closing the same client over and over.
     */
    private void closeClientIfNeeded() {
        if (!clientRunning.compareAndSet(true, false)) {
            return;
        }
        try {
            client.close();
        } catch (Exception e) {
            log.error("failed to close client", e);
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
        NodeWrapper offline;
        NodeWrapper online = null;
        rwLock.writeLock().lock();
        try {
            offline = offlineNodes.remove(id);
            if (offline == null) online = onlineNodes.remove(id);
        } finally {
            rwLock.writeLock().unlock();
        }
        NodeWrapper wrapper = offline != null ? offline : online;
        if (wrapper == null) return;
        wrapper.close();
        // unregister() takes the node out of the map itself rather than through
        // onNodeOffline, so it has to close the client the same way that callback would
        if (online != null && onlineNodes.isEmpty()) {
            closeClientIfNeeded();
        }
    }

    @Override
    public SapientMessage publish(Registration registration, UUID nodeId, Duration timeout)
            throws TimeoutException, InterruptedException {
        return publish(SapientMessage.newBuilder().setRegistration(registration), nodeId, timeout);
    }

    @Override
    public SapientMessage publish(StatusReport status, UUID nodeId, Duration timeout)
            throws TimeoutException, InterruptedException {
        if (status.getReportId().isBlank()) {
            status = status.toBuilder().setReportId(newReportId()).build();
        }
        NodeWrapper node = findNode(nodeId);
        StatusReport prev = node == null ? null : node.getLastStatusReport().get();
        status = withInfo(status, prev);
        SapientMessage message =
                publish(SapientMessage.newBuilder().setStatusReport(status), nodeId, timeout);
        if (node != null) {
            // store only after the send returned without error. A failed send must keep the old
            // value, or the next identical report goes out as INFO_UNCHANGED for content the server
            // never received. compareAndSet loses to a concurrent publish of the same node instead
            // of overwriting its newer value. See CHANGELOG.md §12.
            node.getLastStatusReport().compareAndSet(prev, status);
        }
        return message;
    }

    /**
     * Fills the mandatory {@code info} field of a status report. A goodbye always reports new
     * information, so it is set to INFO_NEW and never de-duplicated. For any other report the value
     * is INFO_UNCHANGED when the content repeats {@code prev} and INFO_NEW otherwise. An explicit
     * INFO_UNCHANGED from the caller is kept as sent.
     *
     * <p>A node that leaves {@code info} unset gets a valid value instead of INFO_UNSPECIFIED.
     *
     * @param prev last status report the node sent successfully, or {@code null} when there is none
     *     — a fresh node, or an unknown (not registered) node. Then there is nothing to compare
     *     with, so the content is new.
     */
    private static StatusReport withInfo(StatusReport status, StatusReport prev) {
        if (status.getSystem() == StatusReport.System.SYSTEM_GOODBYE) {
            return withInfo(status, StatusReport.Info.INFO_NEW);
        }
        if (status.getInfo() == StatusReport.Info.INFO_UNCHANGED) return status;
        boolean unchanged = prev != null && contentEquals(prev, status);
        return withInfo(
                status, unchanged ? StatusReport.Info.INFO_UNCHANGED : StatusReport.Info.INFO_NEW);
    }

    private static StatusReport withInfo(StatusReport status, StatusReport.Info info) {
        return status.getInfo() == info ? status : status.toBuilder().setInfo(info).build();
    }

    /**
     * Compares two status reports for equal content, ignoring fields that change on every report
     * regardless of whether the underlying state changed. {@code report_id} is a mandatory ULID
     * unique to each message, and {@code info} is the very field being decided; including either
     * would make the comparison always unequal and defeat the INFO_UNCHANGED de-duplication.
     */
    private static boolean contentEquals(StatusReport a, StatusReport b) {
        return clearVolatileFields(a).equals(clearVolatileFields(b));
    }

    private static StatusReport clearVolatileFields(StatusReport status) {
        return status.toBuilder().clearInfo().clearReportId().build();
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
        if (detection.getReportId().isBlank()) {
            detection = detection.toBuilder().setReportId(newReportId()).build();
        }
        return publish(SapientMessage.newBuilder().setDetectionReport(detection), nodeId, timeout);
    }

    /**
     * Generates a monotonic ULID for the mandatory {@code report_id} field. Monotonic generation
     * keeps ids time-ordered even when several are created within the same millisecond.
     */
    private static String newReportId() {
        return UlidCreator.getMonotonicUlid().toString();
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
            logBody(log, message);
        }
        client.publish(message, timeout);
        return message;
    }

    /**
     * Renders the full JSON body of a message at DEBUG level. Shared by the outgoing publish path
     * and the incoming {@code _onMessage} path so both directions render bodies identically.
     * Callers guard the invocation with {@code log.isDebugEnabled()} so the JSON is never built at
     * higher log levels.
     */
    static void logBody(Logger log, SapientMessage message) {
        try {
            log.debug("message: {}", JsonFormat.printer().print(message));
        } catch (InvalidProtocolBufferException e) {
            log.debug("message: <serialization failed>", e);
        }
    }

    private static Timestamp timestampNow() {
        Instant now = Instant.now();
        return Timestamp.newBuilder()
                .setSeconds(now.getEpochSecond())
                .setNanos(now.getNano())
                .build();
    }

    /**
     * Stops every registered node and then the underlying client. Each node is stopped completely —
     * its lifecycle thread has terminated and its goodbye has been sent — before the next one is
     * touched, and the client is closed last, so no node loses its goodbye to a client that another
     * node has already closed.
     */
    @Override
    public void close() {
        log.info("stopping the dispatcher gracefully");
        closing.set(true);
        onlineNodes.values().forEach(NodeWrapper::close);
        offlineNodes.values().forEach(NodeWrapper::close);
        onlineNodes.clear();
        offlineNodes.clear();
        closeClientIfNeeded();
        log.info("dispatcher stopped");
    }
}
