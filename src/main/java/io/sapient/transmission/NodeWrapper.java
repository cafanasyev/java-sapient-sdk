package io.sapient.transmission;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import lombok.NonNull;
import uk.gov.dstl.sapientmsg.bsiflex335v2.Registration;
import uk.gov.dstl.sapientmsg.bsiflex335v2.RegistrationAck;
import uk.gov.dstl.sapientmsg.bsiflex335v2.StatusReport;

class NodeWrapper implements AutoCloseable {
    private static final Logger logger = Logger.getLogger(NodeWrapper.class.getName());

    @NonNull final INode node;
    final AtomicBoolean registered = new AtomicBoolean(false);
    final AtomicReference<UUID> fusionNodeId = new AtomicReference<>();
    final AtomicReference<StatusReport> lastStatusReport = new AtomicReference<>();
    final BlockingQueue<RegistrationAck> ackQueue = new ArrayBlockingQueue<>(1);

    private final INodeDispatcher dispatcher;
    private final NodeDispatcherConfig config;
    private final Thread thread;

    NodeWrapper(INode node, INodeDispatcher dispatcher, NodeDispatcherConfig config) {
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
                logger.log(Level.SEVERE, "publish timeout for node: " + node.getNodeId(), e);
            } catch (Exception e) {
                logger.log(Level.SEVERE, "node lifecycle error: " + node.getNodeId(), e);
            }
        }
    }

    private void _run() throws InterruptedException, TimeoutException {
        waitUntilOnline();

        Registration registration = node.getRegistration();
        dispatcher.publish(registration, node.getNodeId(), config.publishTimeout());

        RegistrationAck ack = ackQueue.take();
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
                logger.log(
                        Level.SEVERE,
                        "status report publish timeout for node: " + node.getNodeId(),
                        e);
            }
            Thread.sleep(statusInterval);
        }

        if (!Thread.currentThread().isInterrupted()) {
            try {
                dispatcher.goodbye(node.getNodeId(), config.publishTimeout());
            } catch (TimeoutException e) {
                logger.log(
                        Level.SEVERE, "goodbye publish timeout for node: " + node.getNodeId(), e);
            }
            registered.set(false);
            fusionNodeId.set(null);
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
        thread.interrupt();
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
