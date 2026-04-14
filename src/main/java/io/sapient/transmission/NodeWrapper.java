package io.sapient.transmission;

import static io.sapient.transmission.NodeDispatcher.withTolerance;

import com.google.protobuf.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import uk.gov.dstl.sapientmsg.bsiflex335v2.Registration;
import uk.gov.dstl.sapientmsg.bsiflex335v2.RegistrationAck;
import uk.gov.dstl.sapientmsg.bsiflex335v2.SapientMessage;
import uk.gov.dstl.sapientmsg.bsiflex335v2.StatusReport;

@Slf4j
class NodeWrapper implements AutoCloseable {

    @Getter @NonNull private final INode node;
    @Getter private final AtomicBoolean registered = new AtomicBoolean(false);
    @Getter private final AtomicReference<StatusReport> lastStatusReport = new AtomicReference<>();
    @Getter private final BlockingQueue<RegistrationAck> ackQueue = new ArrayBlockingQueue<>(1);

    private final NodeDispatcher dispatcher;
    private final NodeDispatcherConfig config;
    private final Thread thread;

    // epoch captured from the dispatcher at the moment of successful registration;
    // used to detect whether a grace-period-exceeding reconnect has occurred since then
    private long registrationEpoch;

    NodeWrapper(INode node, NodeDispatcher dispatcher, NodeDispatcherConfig config) {
        this.node = node;
        this.dispatcher = dispatcher;
        this.config = config;
        this.thread = Thread.ofVirtual().name("node-" + node.getNodeId()).start(this::run);
    }

    private void run() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                _run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (TimeoutException e) {
                log.error("publish timeout for node: {}", node.getNodeId(), e);
            } catch (Exception e) {
                log.error("node lifecycle error: {}", node.getNodeId(), e);
            } finally {
                if (!node.isOnline()) {
                    dispatcher.onNodeOffline(node.getNodeId());
                }
            }
        }
    }

    private void _run() throws InterruptedException, TimeoutException {
        waitUntilOnline();
        dispatcher.onNodeOnline(node.getNodeId());

        while (node.isOnline() && !Thread.currentThread().isInterrupted()) {
            Optional<Registration> registration = register();
            if (registration.isEmpty()) {
                Thread.sleep(config.registrationAckTimeout());
                continue;
            }
            runStatusLoop(registration.get());
        }

        if (!Thread.currentThread().isInterrupted()) {
            try {
                sendGoodbye();
            } catch (TimeoutException e) {
                log.error("goodbye publish timeout for node: {}", node.getNodeId(), e);
            }
            registered.set(false);
            lastStatusReport.set(null);
        }
    }

    /**
     * Sends a registration and waits for an ack.
     *
     * @return the accepted {@link Registration}, or empty if rejected
     */
    private Optional<Registration> register() throws InterruptedException, TimeoutException {
        Registration registration = node.getRegistration();
        publish(registration);

        RegistrationAck ack =
                ackQueue.poll(config.registrationAckTimeout().toMillis(), TimeUnit.MILLISECONDS);
        if (ack == null) {
            log.error(
                    "registration ack timeout for node: {} after {}",
                    node.getNodeId(),
                    config.registrationAckTimeout());
            throw new TimeoutException("registration ack timeout for node: " + node.getNodeId());
        }
        node.onRegistrationAck(ack);
        if (!ack.getAcceptance()) return Optional.empty();
        registered.set(true);
        registrationEpoch = dispatcher.reregistrationEpoch();
        return Optional.of(registration);
    }

    /**
     * Publishes status reports until the node goes offline, the thread is interrupted, or a
     * re-registration condition is detected. Sets {@link #registered} to {@code false} when
     * re-registration is needed so the caller's loop will re-enter {@link #register()}.
     */
    private void runStatusLoop(Registration registration) throws InterruptedException {
        var statusInterval = toDuration(registration.getStatusDefinition().getStatusInterval());
        var grace = config.reconnectGracePeriod();
        var serverRetention = withTolerance(statusInterval.multipliedBy(3).plus(grace));
        Instant lastSuccessfulStatusReportAt = Instant.now();

        while (node.isOnline() && !Thread.currentThread().isInterrupted()) {
            if (dispatcher.reregistrationEpoch() != registrationEpoch) {
                log.info(
                        "reconnected after grace period, re-registering node: {}",
                        node.getNodeId());
                registered.set(false);
                return;
            }
            Instant retentionDeadline = lastSuccessfulStatusReportAt.plus(serverRetention);
            if (Instant.now().isAfter(retentionDeadline)) {
                log.info("server retention expired for node: {}, re-registering", node.getNodeId());
                registered.set(false);
                return;
            }
            try {
                var sent = publish(node.getStatusReport());
                lastSuccessfulStatusReportAt = toInstant(sent.getTimestamp());
            } catch (TimeoutException e) {
                log.error("status report publish timeout for node: {}", node.getNodeId(), e);
            }
            Thread.sleep(statusInterval);
        }
    }

    private void waitUntilOnline() throws InterruptedException {
        while (!node.isOnline() && !Thread.currentThread().isInterrupted()) {
            Thread.sleep(config.onlineCheckInterval());
        }
    }

    @Override
    public void close() {
        log.info("stopping node: {} gracefully", node.getNodeId());
        thread.interrupt();
        if (!registered.getAndSet(false)) return;
        try {
            log.info("sending goodbye for the node: {}", node.getNodeId());
            sendGoodbye();
        } catch (TimeoutException | InterruptedException e) {
            log.error("failed to send goodbye for the node: {}", node.getNodeId(), e);
        }
        log.info("node: {} gracefully stopped", node.getNodeId());
    }

    private void sendGoodbye() throws TimeoutException, InterruptedException {
        StatusReport sr = node.getStatusReport();
        if (sr.getSystem() != StatusReport.System.SYSTEM_GOODBYE) {
            sr = sr.toBuilder().setSystem(StatusReport.System.SYSTEM_GOODBYE).build();
        }
        publish(sr);
    }

    private SapientMessage publish(Registration r) throws TimeoutException, InterruptedException {
        return dispatcher.publish(r, node.getNodeId(), config.publishTimeout());
    }

    private SapientMessage publish(StatusReport s) throws TimeoutException, InterruptedException {
        return dispatcher.publish(s, node.getNodeId(), config.publishTimeout());
    }

    static Instant toInstant(Timestamp ts) {
        return Instant.ofEpochSecond(ts.getSeconds(), ts.getNanos());
    }

    static Duration toDuration(Registration.Duration d) {
        long nanosPerUnit =
                switch (d.getUnits()) {
                    case TIME_UNITS_NANOSECONDS -> 1L;
                    case TIME_UNITS_MICROSECONDS -> 1_000L;
                    case TIME_UNITS_MILLISECONDS -> 1_000_000L;
                    case TIME_UNITS_SECONDS -> 1_000_000_000L;
                    case TIME_UNITS_MINUTES -> 60_000_000_000L;
                    case TIME_UNITS_HOURS -> 3_600_000_000_000L;
                    case TIME_UNITS_DAYS -> 86_400_000_000_000L;
                    default -> 1_000_000_000L;
                };
        return Duration.ofNanos((long) (d.getValue() * nanosPerUnit));
    }
}
