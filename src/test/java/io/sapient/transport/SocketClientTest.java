package io.sapient.transport;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import io.sapient.testing.FakeFusionNode;
import io.sapient.transport.health.HealthCheckConfig;
import io.sapient.transport.health.HealthCheckType;
import java.io.IOException;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.AfterEach;
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

    private final FakeFusionNode server = new FakeFusionNode();
    private final BlockingQueue<SapientMessage> received = new LinkedBlockingQueue<>();
    private final BlockingQueue<ConnectionState> states = new LinkedBlockingQueue<>();

    @AfterEach
    void close() {
        server.close();
    }

    @Test
    void testTwoWayCommunication() throws Exception {
        try (var client = client(HealthCheckConfig.DEFAULT)) {
            client.start();
            server.awaitConnects(1);

            server.send(TestServer.GREETING);
            assertEquals(TestServer.GREETING, received.take());

            client.publish(CLIENT_MESSAGE, Duration.ofSeconds(3));
            assertEquals(CLIENT_MESSAGE, server.next());
        }
    }

    @Test
    void testWatchdogDetectsUnreachableServerAndClosesConnection() throws Exception {
        // 100ms check interval, 100ms check timeout, one failure is enough — the health
        // check must detect the server being gone and tear the connection down, without
        // any reliance on SO_TIMEOUT.
        var netcat =
                new HealthCheckConfig(
                        HealthCheckType.NETCAT, Duration.ofMillis(100), Duration.ofMillis(100), 1);
        try (var client = client(netcat)) {
            client.start();
            assertEquals(ConnectionState.CONNECTING, states.take());
            assertEquals(ConnectionState.CONNECTED, states.take());

            // the connection stays open, so readLoop stays blocked on a half-open connection.
            // The health check is the only mechanism that can notice: its next connect is
            // refused, the monitor closes the socket, readLoop wakes and DISCONNECTED fires.
            server.goUnreachable();

            assertEquals(ConnectionState.DISCONNECTED, states.take());
        }
    }

    @Test
    void testNoStateChurnWhenServerUnreachable() throws Exception {
        // every probe fails, so runLoop must stay in DISCONNECTED — no CONNECTING or
        // repeat-DISCONNECTED events — until the server becomes reachable
        var netcat =
                new HealthCheckConfig(
                        HealthCheckType.NETCAT, Duration.ofSeconds(10), Duration.ofMillis(100), 1);
        try (var client = client(netcat)) {
            server.goUnreachable();
            client.start();

            // real time on purpose: the test shows that nothing happens while probes fail,
            // and 500ms is plenty of time for many would-be reconnect cycles
            Thread.sleep(500);

            // no CONNECTING while probes kept failing, and no DISCONNECTED either: the initial
            // state is DISCONNECTED and setState is a no-op when the state does not change
            assertTrue(states.isEmpty(), "state changes while unreachable: " + states);
        }
    }

    @Test
    @Timeout(10)
    void testClientReconnect() throws Exception {
        // TLS needs a real port, so this test keeps TestServer
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
        var client = spy(new SocketClient(provider));
        // bypass probe-before-connect so TestServer's single accept() is consumed by
        // the real client rather than by the probe
        doReturn(true).when(client).probeReachable(any(Duration.class));
        client.subscribe(received::offer);

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

    private SocketClient client(HealthCheckConfig health) {
        var client = new SocketClient(server.sockets(), health, Duration.ofMillis(10));
        client.subscribe(received::add);
        client.addStateChangeListener((state, at) -> states.add(state));
        return client;
    }

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
}
