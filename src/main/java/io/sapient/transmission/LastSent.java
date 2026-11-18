package io.sapient.transmission;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
import uk.gov.dstl.sapientmsg.bsiflex335v2.SapientMessage;
import uk.gov.dstl.sapientmsg.bsiflex335v2.StatusReport;

/**
 * The last status report of one node that the server is known to have. The report is the only
 * element of a queue of one, so holding the report is what allows a publish to go ahead.
 *
 * <p>A publish takes the report out, decides the info of its own report against it, sends, and puts
 * back the report it sent. While the report is out, no other publish of the node can decide or
 * send. The server therefore gets the reports of a node in the order their info was decided.
 *
 * <p>{@link #send} is the only way to the report, and it always puts exactly one value back. When
 * the send throws, that value is "unknown": a failed send may still have reached the server, so the
 * next report is compared with nothing and goes out as INFO_NEW.
 *
 * <p>A report also counts only on the connection it was sent on. A dropped connection may have lost
 * it, so a report from an older connection is unknown as well.
 */
final class LastSent {

    /** Sends one message, given the last status report the server is known to have. */
    @FunctionalInterface
    interface Sender {
        SapientMessage send(Optional<StatusReport> last, Duration timeout)
                throws TimeoutException, InterruptedException;
    }

    /** A status report together with the number of the connection it was sent on. */
    private record Sent(StatusReport report, long connection) {

        /** Connection numbers are never negative, so -1 never matches and UNKNOWN never counts. */
        static final Sent UNKNOWN = new Sent(StatusReport.getDefaultInstance(), -1);

        /** The report, if it was sent on the {@code current} connection. */
        Optional<StatusReport> on(long current) {
            return connection == current ? Optional.of(report) : Optional.empty();
        }
    }

    private final LongSupplier connection;
    private final BlockingQueue<Sent> slot =
            new ArrayBlockingQueue<>(1, true, List.of(Sent.UNKNOWN));

    /**
     * @param connection number of the current connection, never negative. It must change whenever a
     *     connection drops
     */
    LastSent(LongSupplier connection) {
        this.connection = connection;
    }

    /**
     * Runs {@code sender} with the last report and keeps the status report it sent. A message
     * without a status report, such as a registration, leaves the last report unknown.
     *
     * @param timeout covers both the wait for another publish of this node and the send itself
     * @throws TimeoutException if another publish of this node keeps the report past the timeout,
     *     or if {@code sender} times out
     */
    SapientMessage send(Duration timeout, Sender sender)
            throws TimeoutException, InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        Sent taken = slot.poll(timeout.toNanos(), TimeUnit.NANOSECONDS);
        if (taken == null) { // poll returns null only when the wait timed out
            throw new TimeoutException("publish timeout: another status report is in flight");
        }
        Sent next = Sent.UNKNOWN;
        try {
            long current = connection.getAsLong();
            SapientMessage message =
                    sender.send(taken.on(current), Duration.ofNanos(deadline - System.nanoTime()));
            if (message.hasStatusReport()) {
                next = new Sent(message.getStatusReport(), current);
            }
            return message;
        } finally {
            slot.add(next);
        }
    }
}
