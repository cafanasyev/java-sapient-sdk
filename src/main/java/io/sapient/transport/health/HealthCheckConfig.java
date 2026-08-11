package io.sapient.transport.health;

import java.time.Duration;
import lombok.NonNull;

/**
 * Health check settings. Every {@code IClient} implementation takes this same record and rejects
 * what it cannot honour when it is built, so a setting that is not supported fails at start instead
 * of behaving differently at run time.
 *
 * @param type which check to run
 * @param interval how often a check starts. Checks run on this fixed cadence whether the previous
 *     one passed or failed, so the detection delay stays one multiplication
 * @param timeout how long one check may take. Must not be longer than {@code interval}, so two
 *     checks never overlap
 * @param failureThreshold how many checks in a row must fail before the connection is declared
 *     dead. Any success resets the count to zero
 */
public record HealthCheckConfig(
        @NonNull HealthCheckType type,
        @NonNull Duration interval,
        @NonNull Duration timeout,
        int failureThreshold) {

    /** Same mechanism as before this feature, with the protocol-standard failure count. */
    public static final HealthCheckConfig DEFAULT =
            new HealthCheckConfig(
                    HealthCheckType.NETCAT, Duration.ofSeconds(10), Duration.ofSeconds(2), 3);

    public HealthCheckConfig {
        if (interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException("interval must be positive, got " + interval);
        }
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive, got " + timeout);
        }
        if (timeout.compareTo(interval) > 0) {
            throw new IllegalArgumentException(
                    "timeout " + timeout + " must not exceed interval " + interval);
        }
        if (failureThreshold < 1) {
            throw new IllegalArgumentException(
                    "failureThreshold must be at least 1, got " + failureThreshold);
        }
    }

    /**
     * Worst case time between the network dying and the client noticing. The link dies right after
     * a check starts, so {@code failureThreshold} checks still have to run and the last one has to
     * time out.
     *
     * @return the worst-case detection delay
     */
    public Duration connectionLossDetectionDelay() {
        return interval.multipliedBy(failureThreshold).plus(timeout);
    }
}
