package io.sapient.transmission;

import java.time.Duration;
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
                dispatcher.onNodeOffline(node.getNodeId());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (TimeoutException e) {
                log.error("publish timeout for node: {}", node.getNodeId(), e);
            } catch (Exception e) {
                log.error("node lifecycle error: {}", node.getNodeId(), e);
            }
        }
    }

    private void _run() throws InterruptedException, TimeoutException {
        waitUntilOnline();
        dispatcher.onNodeOnline(node.getNodeId());

        Registration registration = node.getRegistration();
        dispatcher.publish(registration, node.getNodeId(), config.publishTimeout());

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
        if (!ack.getAcceptance()) return;
        registered.set(true);

        Duration statusInterval =
                toDuration(registration.getStatusDefinition().getStatusInterval());
        while (!Thread.currentThread().isInterrupted() && node.isOnline()) {
            try {
                dispatcher.publish(
                        node.getStatusReport(), node.getNodeId(), config.publishTimeout());
            } catch (TimeoutException e) {
                log.error("status report publish timeout for node: {}", node.getNodeId(), e);
            }
            Thread.sleep(statusInterval);
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

    private void waitUntilOnline() throws InterruptedException {
        while (!Thread.currentThread().isInterrupted() && !node.isOnline()) {
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
        dispatcher.publish(sr, node.getNodeId(), config.publishTimeout());
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
