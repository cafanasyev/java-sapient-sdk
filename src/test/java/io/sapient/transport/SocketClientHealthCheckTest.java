package io.sapient.transport;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import io.sapient.transport.health.HealthCheckConfig;
import io.sapient.transport.health.HealthCheckType;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import uk.gov.dstl.sapientmsg.bsiflex335v2.SapientMessage;
import uk.gov.dstl.sapientmsg.bsiflex335v2.StatusReport;

@Execution(ExecutionMode.CONCURRENT)
class SocketClientHealthCheckTest {

    private static SapientMessage msg(String mode) {
        return SapientMessage.newBuilder()
                .setStatusReport(StatusReport.newBuilder().setMode(mode).build())
                .build();
    }

    private static HealthCheckConfig config(HealthCheckType type) {
        return new HealthCheckConfig(type, Duration.ofSeconds(10), Duration.ofSeconds(2), 3);
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

    @Test
    @Timeout(value = 15, unit = SECONDS)
    void testEchoSentinelIsConsumedAndNotDelivered() throws Exception {
        var received = new ArrayBlockingQueue<SapientMessage>(8);
        var provider = mock(ISocketProvider.class);
        var pipe = new MockPipe();
        // build the socket before stubbing: MockPipe.socket() stubs its own mock, and
        // Mockito rejects a stubbing that starts inside an unfinished one
        Socket socket = pipe.socket();
        when(provider.get()).thenReturn(socket);

        var client =
                spy(
                        new SocketClient(
                                provider, config(HealthCheckType.ECHO), Duration.ofSeconds(1)));
        doReturn(true).when(client).probeReachable(any(Duration.class));
        client.subscribe(received::offer);
        client.start();

        // the peer echoes our ping back: prefix 0, no body
        pipe.sendRawPrefix(0x00000000);
        pipe.send(msg("after-pong"));

        // the sentinel must not reach the consumer, and the real message must still arrive
        assertEquals(msg("after-pong"), received.poll(6, SECONDS));

        client.close();
    }

    @Test
    @Timeout(value = 15, unit = SECONDS)
    void testPingpongSentinelIsConsumedAndNotDelivered() throws Exception {
        var received = new ArrayBlockingQueue<SapientMessage>(8);
        var provider = mock(ISocketProvider.class);
        var pipe = new MockPipe();
        Socket socket = pipe.socket();
        when(provider.get()).thenReturn(socket);

        var client =
                spy(
                        new SocketClient(
                                provider, config(HealthCheckType.PINGPONG), Duration.ofSeconds(1)));
        doReturn(true).when(client).probeReachable(any(Duration.class));
        client.subscribe(received::offer);
        client.start();

        // 0xFFFFFFFF is the pong in this mode. Without the sentinel branch it would trip
        // the frame size guard and drop the connection.
        pipe.sendRawPrefix(0xFFFFFFFF);
        pipe.send(msg("after-pong"));

        assertEquals(msg("after-pong"), received.poll(6, SECONDS));

        client.close();
    }

    @Test
    @Timeout(value = 15, unit = SECONDS)
    void testNetcatModeStillTreatsAllOnesAsABadFrame() throws Exception {
        // locks the frame size guard from CHANGELOG §13: the default mode has no
        // sentinels, so 0xFFFFFFFF is still garbage and still costs one reconnect
        var received = new ArrayBlockingQueue<SapientMessage>(8);
        var provider = mock(ISocketProvider.class);
        var bad = new MockPipe();
        var good = new MockPipe();
        Socket badSocket = bad.socket();
        Socket goodSocket = good.socket();
        when(provider.get()).thenReturn(badSocket, goodSocket);
        // NETCAT opens its own connection, so it needs an address. Nothing listens on it:
        // the check only has to be buildable, and at a 10s interval it never fires here.
        when(provider.host()).thenReturn("localhost");
        when(provider.port()).thenReturn(1);

        var client =
                spy(
                        new SocketClient(
                                provider, config(HealthCheckType.NETCAT), Duration.ofSeconds(1)));
        doReturn(true).when(client).probeReachable(any(Duration.class));
        client.subscribe(received::offer);
        client.start();

        bad.sendRawPrefix(0xFFFFFFFF);
        good.send(msg("alive"));

        assertEquals(msg("alive"), received.poll(6, SECONDS));

        client.close();
    }
}
