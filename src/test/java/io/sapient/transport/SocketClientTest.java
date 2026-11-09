package io.sapient.transport;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import io.sapient.transport.health.HealthCheckConfig;
import io.sapient.transport.health.HealthCheckType;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import uk.gov.dstl.sapientmsg.bsiflex335v2.Registration;
import uk.gov.dstl.sapientmsg.bsiflex335v2.SapientMessage;

@Execution(ExecutionMode.CONCURRENT)
class SocketClientTest {

    private static final SapientMessage CLIENT_MESSAGE =
            SapientMessage.newBuilder().setRegistration(Registration.getDefaultInstance()).build();

    @FunctionalInterface
    private interface SocketSupplier {
        Socket get() throws IOException;
    }

    private static class TestSocketProvider implements ISocketProvider {
        private final String host;
        private final IntSupplier portSupplier;
        private final SocketSupplier socketSupplier;

        TestSocketProvider(String host, IntSupplier portSupplier, SocketSupplier socketSupplier) {
            this.host = host;
            this.portSupplier = portSupplier;
            this.socketSupplier = socketSupplier;
        }

        @Override
        public Socket get() throws IOException {
            return socketSupplier.get();
        }

        @Override
        public String host() {
            return host;
        }

        @Override
        public int port() {
            return portSupplier.getAsInt();
        }
    }

    private static SocketClient newClient(
            ISocketProvider provider, ArrayBlockingQueue<SapientMessage> received) {
        SocketClient client = spy(new SocketClient(provider));
        // bypass probe-before-connect so TestServer's single accept() is consumed by
        // the real client rather than by the probe
        doReturn(true).when(client).probeReachable(any(Duration.class));
        client.subscribe(received::offer);
        return client;
    }

    @Test
    @Timeout(3)
    void testTwoWayCommunication() throws Exception {
        var server = new TestServer();
        int port = server.getLocalPort();
        var received = new ArrayBlockingQueue<SapientMessage>(1);
        var provider =
                new TestSocketProvider(
                        "localhost", () -> port, () -> new Socket("localhost", port));
        var client = newClient(provider, received);

        try (server;
                client) {
            Thread.startVirtualThread(server);
            client.start();

            assertEquals(TestServer.GREETING, received.poll(3, SECONDS));
            client.publish(CLIENT_MESSAGE, Duration.ofSeconds(3));
            assertEquals(CLIENT_MESSAGE, server.getClientMessages().poll(3, SECONDS));
        }
    }

    @Test
    @Timeout(5)
    void testWatchdogDetectsUnreachableServerAndClosesConnection() throws Exception {
        var serverSocket = new ServerSocket(0);
        int port = serverSocket.getLocalPort();

        var provider =
                new TestSocketProvider(
                        "localhost", () -> port, () -> new Socket("localhost", port));
        var states = new LinkedBlockingQueue<ConnectionState>();
        // 100ms check interval, 100ms check timeout, one failure is enough — the health
        // check must detect the server being gone and tear the connection down within the
        // test's 5s budget, without any reliance on SO_TIMEOUT.
        var client =
                spy(
                        new SocketClient(
                                provider,
                                new HealthCheckConfig(
                                        HealthCheckType.NETCAT,
                                        Duration.ofMillis(100),
                                        Duration.ofMillis(100),
                                        1),
                                Duration.ofMillis(10)));
        // stateful stub for the pre-connect probe only: true lets the client reach the
        // server's single accept(). The NETCAT health check opens its own connection and
        // does not go through this method.
        var probeAlive = new AtomicBoolean(true);
        doAnswer(inv -> probeAlive.get()).when(client).probeReachable(any(Duration.class));
        client.addStateChangeListener((s, ts) -> states.add(s));

        try (client) {
            client.start();
            assertEquals(ConnectionState.CONNECTING, states.poll(2, SECONDS));

            // accept and send a greeting to prove the connection works
            Socket accepted = serverSocket.accept();
            writeFramed(accepted.getOutputStream(), TestServer.GREETING.toByteArray());

            assertEquals(ConnectionState.CONNECTED, states.poll(2, SECONDS));

            // the server stops listening while the accepted socket stays open, so readLoop
            // stays blocked on a half-open TCP connection. The health check is the only
            // mechanism that can notice: its next connect is refused, the monitor closes
            // the socket, readLoop wakes and DISCONNECTED fires.
            serverSocket.close();
            probeAlive.set(false);

            assertEquals(ConnectionState.DISCONNECTED, states.poll(2, SECONDS));
            accepted.close();
        }
    }

    @Test
    @Timeout(3)
    void testNoStateChurnWhenServerUnreachable() throws Exception {
        // grab an ephemeral port, release it, and point the client at it so every
        // probe fails. runLoop must stay in DISCONNECTED — no CONNECTING or
        // repeat-DISCONNECTED events — until the server becomes reachable.
        ServerSocket tmp = new ServerSocket(0);
        int port = tmp.getLocalPort();
        tmp.close();

        var provider =
                new TestSocketProvider(
                        "localhost", () -> port, () -> new Socket("localhost", port));
        var states = new LinkedBlockingQueue<ConnectionState>();
        var client =
                new SocketClient(
                        provider,
                        new HealthCheckConfig(
                                HealthCheckType.NETCAT,
                                Duration.ofSeconds(10),
                                Duration.ofMillis(100),
                                1),
                        Duration.ofMillis(10));
        client.addStateChangeListener((s, ts) -> states.add(s));

        try (client) {
            client.start();
            // plenty of time for many would-be reconnect cycles
            Thread.sleep(500);

            // no CONNECTING should have been emitted while probes kept failing
            assertFalse(
                    states.contains(ConnectionState.CONNECTING),
                    "probe-before-connect must suppress CONNECTING while server is unreachable");
            // and no DISCONNECTED either — initial state is DISCONNECTED and setState is
            // a no-op when the state does not change
            assertFalse(
                    states.contains(ConnectionState.DISCONNECTED),
                    "no spurious DISCONNECTED transitions while server is unreachable");
        }
    }

    private static void writeFramed(OutputStream out, byte[] data) throws IOException {
        out.write(
                ByteBuffer.allocate(4 + data.length)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(data.length)
                        .put(data)
                        .array());
        out.flush();
    }

    @Test
    @Timeout(10)
    void testClientReconnect() throws Exception {
        var tls = new TestDerTlsConfig();
        var serverCtx = tls.serverContext();
        var clientCtx = tls.clientContext();
        var server = new TestServer(serverCtx);
        var port = new AtomicInteger(server.getLocalPort());
        var received = new ArrayBlockingQueue<SapientMessage>(1);
        var provider =
                new TestSocketProvider(
                        "localhost",
                        port::get,
                        () -> clientCtx.getSocketFactory().createSocket("localhost", port.get()));
        var client = newClient(provider, received);

        try (client) {
            Thread.startVirtualThread(server);
            client.start();

            assertEquals(TestServer.GREETING, received.poll(3, SECONDS));
            client.publish(CLIENT_MESSAGE, Duration.ofSeconds(3));
            assertEquals(CLIENT_MESSAGE, server.getClientMessages().poll(3, SECONDS));

            server.close();

            var newServer = new TestServer(serverCtx);
            port.set(newServer.getLocalPort());
            Thread.startVirtualThread(newServer);

            assertEquals(TestServer.GREETING, received.poll(5, SECONDS));
            client.publish(CLIENT_MESSAGE, Duration.ofSeconds(5));
            assertEquals(CLIENT_MESSAGE, newServer.getClientMessages().poll(3, SECONDS));

            newServer.close();
        }
    }
}
