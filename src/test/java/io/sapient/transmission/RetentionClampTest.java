package io.sapient.transmission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RetentionClampTest {

    @Test
    void testNormalBudgetIsUnchanged() {
        // 3 × 10s + 2min − 32s = 118s
        Duration retention =
                NodeWrapper.serverRetention(
                        Duration.ofSeconds(10), Duration.ofMinutes(2), Duration.ofSeconds(32));
        assertEquals(Duration.ofSeconds(118), retention);
    }

    @Test
    void testOversizedDetectionDelayIsClampedAtZero() {
        // a 10 minute detection delay eats the whole budget. A negative retention would
        // mean "re-register on every tick", so clamp instead.
        Duration retention =
                NodeWrapper.serverRetention(
                        Duration.ofSeconds(10), Duration.ofMinutes(2), Duration.ofMinutes(10));
        assertEquals(Duration.ZERO, retention);
    }

    @Test
    void testExactlyZeroBudgetIsAllowed() {
        Duration retention =
                NodeWrapper.serverRetention(
                        Duration.ofSeconds(10), Duration.ofMinutes(2), Duration.ofSeconds(150));
        assertEquals(Duration.ZERO, retention);
    }

    @Test
    void testResultIsNeverNegative() {
        Duration retention =
                NodeWrapper.serverRetention(
                        Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofHours(1));
        assertTrue(retention.isZero() || retention.isPositive(), "retention was " + retention);
    }
}
