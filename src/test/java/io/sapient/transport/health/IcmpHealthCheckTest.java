package io.sapient.transport.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class IcmpHealthCheckTest {

    @Test
    void testAliveWhenPingSucceeds() {
        var check = new IcmpHealthCheck("10.0.0.1", Duration.ofSeconds(2), (host, timeout) -> true);
        assertTrue(check.check());
    }

    @Test
    void testDeadWhenPingFails() {
        var check =
                new IcmpHealthCheck("10.0.0.1", Duration.ofSeconds(2), (host, timeout) -> false);
        assertFalse(check.check());
    }

    @Test
    void testPassesHostAndTimeoutToTheRunner() {
        var seenHost = new AtomicReference<String>();
        var seenTimeout = new AtomicReference<Duration>();
        var check =
                new IcmpHealthCheck(
                        "10.0.0.1",
                        Duration.ofSeconds(2),
                        (host, timeout) -> {
                            seenHost.set(host);
                            seenTimeout.set(timeout);
                            return true;
                        });

        check.check();

        assertEquals("10.0.0.1", seenHost.get());
        assertEquals(Duration.ofSeconds(2), seenTimeout.get());
    }
}
