package io.sapient.transmission;

import java.time.Duration;
import java.util.Random;

/**
 * Pure-function helpers for spreading per-node status reports across time so multiple nodes
 * registered with the same {@code statusInterval} don't all fire at the same instant.
 *
 * <p>See CHANGELOG.md §5.
 */
final class Jitter {

    private Jitter() {}

    /**
     * Per-cycle jitter factor: each sleep is multiplied by a value in {@code [1 - JITTER_FACTOR, 1
     * + JITTER_FACTOR)}.
     */
    static final double JITTER_FACTOR = 0.10;

    /**
     * Window for the one-time uniform-random delay applied before each registration message,
     * spreading the registration storm that occurs when many clients reconnect together (e.g. after
     * a fusion-server restart). 2 seconds is small relative to typical app startup budgets and
     * small relative to {@code reconnectGracePeriod} (default 2 minutes), so it never risks the
     * server's grace timer expiring before re-registration completes.
     */
    static final Duration REGISTRATION_JITTER_WINDOW = Duration.ofSeconds(2);

    /**
     * Returns a per-cycle sleep duration uniformly distributed in {@code [statusInterval × (1 -
     * JITTER_FACTOR), statusInterval × (1 + JITTER_FACTOR))}. Applied each iteration of the status
     * loop so two nodes that drew similar phase offsets slowly drift apart instead of staying glued
     * together.
     */
    static Duration jitteredSleep(Duration statusInterval, Random rng) {
        double factor = (1.0 - JITTER_FACTOR) + rng.nextDouble() * (2 * JITTER_FACTOR);
        return Duration.ofNanos((long) (statusInterval.toNanos() * factor));
    }

    /**
     * Returns a one-time phase offset uniformly distributed in {@code [0, statusInterval)}. Applied
     * once at the start of each (re-)registered status loop so two nodes with the same {@code
     * statusInterval} don't begin their loops in lockstep.
     */
    static Duration phaseOffset(Duration statusInterval, Random rng) {
        long bound = statusInterval.toNanos();
        if (bound <= 0) return Duration.ZERO;
        return Duration.ofNanos(rng.nextLong(bound));
    }
}
