package io.sapient.transport.health;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import lombok.NonNull;

/**
 * Keepalive on the connection itself: send a ping prefix, wait for the peer to say something back.
 * Covers {@code ECHO} and {@code PINGPONG} — only the ping value differs, and the answer is not
 * inspected.
 *
 * <p>Any inbound frame counts as the answer, not only the pong. A ping proves the pipe is alive; so
 * does a {@code RegistrationAck}. This also makes a wrong-mode setup harmless: a server with
 * keepalive off answers a zero-length frame with a validation {@code Error}, and that {@code Error}
 * passes the check.
 *
 * <p>The client never answers a ping from the peer. It is an initiator only, which is what keeps
 * two {@code ECHO} peers from pinging each other forever.
 */
public class InBandHealthCheck implements IHealthCheck {

    private final int pingPrefix;
    private final Duration timeout;
    private final IPrefixWriter writer;

    /** Released by the read path on every inbound frame. Never holds more than one permit. */
    private final Semaphore inbound = new Semaphore(0);

    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP2",
            justification =
                    "IPrefixWriter is an injected interface dependency, not a mutable data structure — defensive copy is not applicable")
    public InBandHealthCheck(
            int pingPrefix, @NonNull Duration timeout, @NonNull IPrefixWriter writer) {
        this.pingPrefix = pingPrefix;
        this.timeout = timeout;
        this.writer = writer;
    }

    @Override
    public boolean check() {
        // a frame arrived since the last check, so the link is already proven alive and
        // there is no need to ping. Taking the permit is what stops it counting twice:
        // the next check starts with nothing pending.
        if (inbound.tryAcquire()) {
            return true;
        }

        if (!writer.writePrefix(pingPrefix, timeout)) {
            // the write path was blocked for the whole timeout. Still alive if the peer
            // sent us something while we queued.
            return inbound.tryAcquire();
        }

        try {
            return inbound.tryAcquire(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public void onInbound() {
        // one permit is enough: a check consumes at most one
        if (inbound.availablePermits() == 0) {
            inbound.release();
        }
    }
}
