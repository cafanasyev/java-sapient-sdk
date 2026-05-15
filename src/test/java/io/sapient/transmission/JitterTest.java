package io.sapient.transmission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Random;
import org.junit.jupiter.api.Test;

class JitterTest {

    @Test
    void phaseOffsetWithinRange() {
        Random rng = new Random(42L);
        Duration interval = Duration.ofMillis(100);
        for (int i = 0; i < 1000; i++) {
            Duration offset = Jitter.phaseOffset(interval, rng);
            assertTrue(offset.compareTo(Duration.ZERO) >= 0, "offset must be >= 0, got " + offset);
            assertTrue(offset.compareTo(interval) < 0, "offset must be < interval, got " + offset);
        }
    }

    @Test
    void phaseOffsetZeroForZeroInterval() {
        assertEquals(Duration.ZERO, Jitter.phaseOffset(Duration.ZERO, new Random(1L)));
    }

    @Test
    void phaseOffsetIsDeterministicWithSeededRng() {
        Duration first = Jitter.phaseOffset(Duration.ofMillis(100), new Random(42L));
        Duration second = Jitter.phaseOffset(Duration.ofMillis(100), new Random(42L));
        assertEquals(first, second);
    }

    @Test
    void jitteredSleepWithinTenPercentOfInterval() {
        Random rng = new Random(42L);
        Duration interval = Duration.ofMillis(100);
        long lower = 90_000_000L; // 90ms in nanos
        long upper = 110_000_000L; // 110ms in nanos
        for (int i = 0; i < 1000; i++) {
            Duration sleep = Jitter.jitteredSleep(interval, rng);
            assertTrue(sleep.toNanos() >= lower, "sleep must be >= 90ms, got " + sleep);
            assertTrue(sleep.toNanos() < upper, "sleep must be < 110ms, got " + sleep);
        }
    }

    @Test
    void jitteredSleepIsDeterministicWithSeededRng() {
        Duration first = Jitter.jitteredSleep(Duration.ofMillis(100), new Random(42L));
        Duration second = Jitter.jitteredSleep(Duration.ofMillis(100), new Random(42L));
        assertEquals(first, second);
    }
}
