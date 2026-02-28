package io.sapient.transmission;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.NonNull;
import uk.gov.dstl.sapientmsg.bsiflex335v2.Registration;
import uk.gov.dstl.sapientmsg.bsiflex335v2.RegistrationAck;

class NodeWrapper implements AutoCloseable {
    @NonNull final INode node;
    final AtomicBoolean registered = new AtomicBoolean(false);
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
        try {
            while (!Thread.currentThread().isInterrupted()) {
                waitUntilOnline();

                Registration registration = node.getRegistration();
                dispatcher.publish(registration, node.getNodeId(), config.publishTimeout());

                RegistrationAck ack = ackQueue.take();
                node.onRegistrationAck(ack);
                if (!ack.getAcceptance()) continue;
                registered.set(true);

                Duration interval =
                        Duration.ofMillis(
                                toMillis(registration.getStatusDefinition().getStatusInterval()));
                while (!Thread.currentThread().isInterrupted()) {
                    Thread.sleep(interval);
                    if (!node.isOnline()) break;
                    dispatcher.publish(
                            node.getStatusReport(), node.getNodeId(), config.publishTimeout());
                }

                if (!Thread.currentThread().isInterrupted()) {
                    dispatcher.goodbye(node.getNodeId(), config.publishTimeout());
                    registered.set(false);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (TimeoutException e) {
            throw new RuntimeException(e);
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

    static long toMillis(Registration.Duration duration) {
        float value = duration.getValue();
        return (long)
                switch (duration.getUnits()) {
                    case TIME_UNITS_NANOSECONDS -> value / 1_000_000;
                    case TIME_UNITS_MICROSECONDS -> value / 1_000;
                    case TIME_UNITS_MILLISECONDS -> value;
                    case TIME_UNITS_SECONDS -> value * 1_000;
                    case TIME_UNITS_MINUTES -> value * 60_000;
                    case TIME_UNITS_HOURS -> value * 3_600_000;
                    case TIME_UNITS_DAYS -> value * 86_400_000;
                    default -> value * 1_000; // default to seconds
                };
    }
}
