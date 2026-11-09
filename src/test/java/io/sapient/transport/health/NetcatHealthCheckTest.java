package io.sapient.transport.health;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class NetcatHealthCheckTest {

    @Test
    @Timeout(10)
    void testListeningPortIsAlive() throws IOException {
        try (ServerSocket server = new ServerSocket(0)) {
            var check =
                    new NetcatHealthCheck(
                            "localhost", server.getLocalPort(), Duration.ofSeconds(2));
            assertTrue(check.check());
        }
    }

    @Test
    @Timeout(10)
    void testClosedPortIsDead() throws IOException {
        int port;
        try (ServerSocket server = new ServerSocket(0)) {
            port = server.getLocalPort();
        }
        // the socket is closed now, so nothing accepts on that port
        var check = new NetcatHealthCheck("localhost", port, Duration.ofSeconds(2));
        assertFalse(check.check());
    }

    @Test
    @Timeout(10)
    void testUnresolvableHostIsDead() {
        var check = new NetcatHealthCheck("no-such-host.invalid", 6969, Duration.ofMillis(500));
        assertFalse(check.check());
    }
}
