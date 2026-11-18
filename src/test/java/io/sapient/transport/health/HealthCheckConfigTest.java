package io.sapient.transport.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class HealthCheckConfigTest {

    private static HealthCheckConfig config(Duration interval, Duration timeout, int threshold) {
        return new HealthCheckConfig(HealthCheckType.NETCAT, interval, timeout, threshold);
    }

    @Test
    void testDetectionDelayIsThresholdTimesIntervalPlusTimeout() {
        var c = config(Duration.ofSeconds(10), Duration.ofSeconds(2), 3);
        assertEquals(Duration.ofSeconds(32), c.connectionLossDetectionDelay());
    }

    @Test
    void testDetectionDelayWithThresholdOne() {
        var c = config(Duration.ofSeconds(10), Duration.ofSeconds(2), 1);
        assertEquals(Duration.ofSeconds(12), c.connectionLossDetectionDelay());
    }

    @Test
    void testDefaultsMatchTheDocumentedValues() {
        assertEquals(HealthCheckType.NETCAT, HealthCheckConfig.DEFAULT.type());
        assertEquals(Duration.ofSeconds(10), HealthCheckConfig.DEFAULT.interval());
        assertEquals(Duration.ofSeconds(2), HealthCheckConfig.DEFAULT.timeout());
        assertEquals(3, HealthCheckConfig.DEFAULT.failureThreshold());
        assertEquals(
                Duration.ofSeconds(32), HealthCheckConfig.DEFAULT.connectionLossDetectionDelay());
    }

    @Test
    void testTimeoutEqualToIntervalIsAllowed() {
        // the boundary is legal: two checks still never overlap
        var c = config(Duration.ofSeconds(2), Duration.ofSeconds(2), 1);
        assertEquals(Duration.ofSeconds(4), c.connectionLossDetectionDelay());
    }

    @Test
    void testTimeoutAboveIntervalIsRejected() {
        var e =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> config(Duration.ofSeconds(2), Duration.ofSeconds(3), 1));
        assertTrue(e.getMessage().contains("timeout"), e.getMessage());
    }

    @Test
    void testThresholdBelowOneIsRejected() {
        var e =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> config(Duration.ofSeconds(10), Duration.ofSeconds(2), 0));
        assertTrue(e.getMessage().contains("failureThreshold"), e.getMessage());
    }

    @Test
    void testZeroIntervalIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> config(Duration.ZERO, Duration.ZERO, 1));
    }

    @Test
    void testNullTypeIsRejected() {
        assertThrows(
                NullPointerException.class,
                () ->
                        new HealthCheckConfig(
                                null, Duration.ofSeconds(10), Duration.ofSeconds(2), 3));
    }
}
