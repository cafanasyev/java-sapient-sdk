package io.sapient.transport.health;

import java.time.Duration;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

/**
 * Runs the health check on a fixed cadence and declares the connection dead after {@code
 * failureThreshold} failures in a row. One monitor per connection.
 *
 * <p>Checks start every {@code interval}, whether the previous one passed or failed. The next check
 * is anchored on the previous check's <b>start</b>, not its end — anchoring on the end would leave
 * the loop already past the interval after a failure, so the retries would run back to back and N
 * failures would only measure one outage of {@code N × timeout} instead of tolerating a short blip.
 * Fixed cadence is what SSH, BGP, OSPF, BFD, Kubernetes and gRPC keepalive all do.
 *
 * <p>Any inbound frame is proof of life and pushes the next check back by a full interval. On a
 * busy link the check may never run, which is correct: a half-open socket has no inbound traffic,
 * so checks fire exactly when they are needed.
 */
@Slf4j
public class HealthMonitor implements Runnable {

    private final IHealthCheck check;
    private final HealthCheckConfig config;
    private final Runnable onDead;

    private volatile long lastInboundNanos = System.nanoTime();
    private long lastCheckStartedNanos = System.nanoTime();

    /**
     * @param check the check to run
     * @param config cadence and failure count
     * @param onDead run once, when the check has failed {@code failureThreshold} times in a row.
     *     The client closes the socket here, which unblocks the read loop and drives the
     *     DISCONNECTED transition through the existing path
     */
    public HealthMonitor(
            @NonNull IHealthCheck check,
            @NonNull HealthCheckConfig config,
            @NonNull Runnable onDead) {
        this.check = check;
        this.config = config;
        this.onDead = onDead;
    }

    /**
     * Told by the read path that a frame arrived from the peer. Pushes the next check back and
     * hands the signal to the check, which is how an in-band ping gets its answer.
     */
    public void onInbound() {
        lastInboundNanos = System.nanoTime();
        check.onInbound();
    }

    @Override
    public void run() {
        long intervalNanos = config.interval().toNanos();
        int failures = 0;

        while (!Thread.currentThread().isInterrupted()) {
            long anchor = Math.max(lastInboundNanos, lastCheckStartedNanos);
            long waitNanos = anchor + intervalNanos - System.nanoTime();
            if (waitNanos > 0) {
                if (!sleepNanos(waitNanos)) {
                    return;
                }
                continue;
            }

            lastCheckStartedNanos = System.nanoTime();
            if (check.check()) {
                failures = 0;
                continue;
            }

            failures++;
            log.warn("health check failed ({} of {})", failures, config.failureThreshold());
            if (failures >= config.failureThreshold()) {
                log.error(
                        "health check failed {} times in a row, dropping the connection", failures);
                onDead.run();
                return;
            }
        }
    }

    /**
     * @return {@code false} if the thread was interrupted and the loop must stop
     */
    private boolean sleepNanos(long nanos) {
        try {
            Thread.sleep(Duration.ofNanos(nanos));
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
