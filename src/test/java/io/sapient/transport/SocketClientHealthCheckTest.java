package io.sapient.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import io.sapient.testing.FakeFusionNode;
import io.sapient.transport.health.HealthCheckConfig;
import io.sapient.transport.health.HealthCheckType;
import io.sapient.transport.health.KeepalivePrefixes;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import uk.gov.dstl.sapientmsg.bsiflex335v2.SapientMessage;
import uk.gov.dstl.sapientmsg.bsiflex335v2.StatusReport;

@Execution(ExecutionMode.CONCURRENT)
class SocketClientHealthCheckTest {

    private final FakeFusionNode server = new FakeFusionNode();
    private final BlockingQueue<SapientMessage> received = new LinkedBlockingQueue<>();

    @AfterEach
    void close() {
        server.close();
    }

    @Test
    void testTransportNativeIsRejected() {
        var e =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                new SocketClient(
                                        mock(ISocketProvider.class),
                                        config(HealthCheckType.TRANSPORT_NATIVE),
                                        Duration.ofSeconds(1)));
        assertEquals(
                "SocketClient does not support TRANSPORT_NATIVE: a raw socket has no built-in"
                        + " keepalive",
                e.getMessage());
    }

    @Test
    void testDetectionDelayComesFromTheHealthCheckConfig() {
        var client =
                new SocketClient(
                        mock(ISocketProvider.class),
                        config(HealthCheckType.NETCAT),
                        Duration.ofSeconds(1));
        // 3 × 10s + 2s
        assertEquals(Duration.ofSeconds(32), client.connectionLossDetectionDelay());
    }

    @Test
    void testDefaultConstructorUsesTheDefaultHealthCheck() {
        var client = new SocketClient(mock(ISocketProvider.class));
        assertEquals(
                HealthCheckConfig.DEFAULT.connectionLossDetectionDelay(),
                client.connectionLossDetectionDelay());
    }

    /**
     * The peer's answer to a ping is a bare prefix: 0 for ECHO, 0xFFFFFFFF for PINGPONG. It must
     * not reach the consumer, as an empty message or as a frame too large to read.
     */
    @ParameterizedTest
    @EnumSource(names = {"ECHO", "PINGPONG"})
    void testKeepaliveAnswerIsConsumedAndNotDelivered(HealthCheckType type) throws Exception {
        try (var client = startedClient(type)) {
            server.awaitConnects(1);

            server.sendRawPrefix(KeepalivePrefixes.of(type).pong());
            server.send(msg("after-pong"));

            assertEquals(msg("after-pong"), received.take());
        }
    }

    @Test
    void testNetcatModeStillTreatsAllOnesAsABadFrame() throws Exception {
        // locks the frame size guard from CHANGELOG §13: the default mode has no
        // sentinels, so 0xFFFFFFFF is still garbage and still costs one reconnect
        try (var client = startedClient(HealthCheckType.NETCAT)) {
            server.awaitConnects(1);

            server.sendRawPrefix(0xFFFFFFFF);

            server.awaitConnects(2);
            server.send(msg("alive"));
            assertEquals(msg("alive"), received.take());
        }
    }

    /** A client whose checks run every 10 s, so none fires during a test. */
    private SocketClient startedClient(HealthCheckType type) {
        var client = new SocketClient(server.sockets(), config(type), Duration.ofMillis(10));
        client.subscribe(received::add);
        client.start();
        return client;
    }

    private static HealthCheckConfig config(HealthCheckType type) {
        return new HealthCheckConfig(type, Duration.ofSeconds(10), Duration.ofSeconds(2), 3);
    }

    private static SapientMessage msg(String mode) {
        return SapientMessage.newBuilder()
                .setStatusReport(StatusReport.newBuilder().setMode(mode).build())
                .build();
    }
}
