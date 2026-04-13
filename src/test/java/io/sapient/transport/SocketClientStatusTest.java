package io.sapient.transport;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

@Execution(ExecutionMode.CONCURRENT)
class SocketClientStatusTest {

    private static SocketProvider plainProvider(int port) {
        return new SocketProvider() {
            @Override
            public Socket get() throws IOException {
                return new Socket("localhost", port);
            }

            @Override
            public String host() {
                return "localhost";
            }

            @Override
            public int port() {
                return port;
            }
        };
    }

    @Test
    void testInitialStateIsDisconnected() {
        var client = new SocketClient(mock(SocketProvider.class));
        assertEquals(ConnectionState.DISCONNECTED, client.getState());
        assertFalse(client.isConnected());
        client.close();
    }

    @Test
    void testIsConnectedOnlyWhenStateIsConnected() {
        var client = new SocketClient(mock(SocketProvider.class));
        assertFalse(client.isConnected()); // DISCONNECTED initially
        client.close();
        assertFalse(client.isConnected()); // CLOSED after close
    }

    @Test
    void testStateIsClosedAfterClientClose() throws InterruptedException {
        var client = new SocketClient(mock(SocketProvider.class));
        var states = new LinkedBlockingQueue<ConnectionState>();
        client.addStateChangeListener((s, ts) -> states.add(s));

        client.close();

        assertEquals(ConnectionState.CLOSED, client.getState());
        assertEquals(ConnectionState.CLOSED, states.poll(1, SECONDS));
    }

    @Test
    @Timeout(5)
    void testStateTransitionsOnConnect() throws Exception {
        var server = new TestServer();
        Thread.startVirtualThread(server);
        var states = new LinkedBlockingQueue<ConnectionState>();
        var client = new SocketClient(plainProvider(server.getLocalPort()));
        client.addStateChangeListener((s, ts) -> states.add(s));

        try (server;
                client) {
            client.start();

            assertEquals(ConnectionState.CONNECTING, states.poll(2, SECONDS));
            assertEquals(ConnectionState.CONNECTED, states.poll(2, SECONDS));
            assertTrue(client.isConnected());
        }
    }

    @Test
    @Timeout(5)
    void testStateBecomesDisconnectedAfterServerClose() throws Exception {
        var server = new TestServer();
        Thread.startVirtualThread(server);
        var states = new LinkedBlockingQueue<ConnectionState>();
        var client = new SocketClient(plainProvider(server.getLocalPort()));
        client.addStateChangeListener((s, ts) -> states.add(s));

        try (client) {
            client.start();

            assertEquals(ConnectionState.CONNECTING, states.poll(2, SECONDS));
            assertEquals(ConnectionState.CONNECTED, states.poll(2, SECONDS));

            server.close();

            assertEquals(ConnectionState.DISCONNECTED, states.poll(3, SECONDS));
        }
    }

    @Test
    void testMultipleListenersAllNotified() throws InterruptedException {
        var client = new SocketClient(mock(SocketProvider.class));
        var states1 = new LinkedBlockingQueue<ConnectionState>();
        var states2 = new LinkedBlockingQueue<ConnectionState>();
        client.addStateChangeListener((s, ts) -> states1.add(s));
        client.addStateChangeListener((s, ts) -> states2.add(s));

        client.close();

        assertEquals(ConnectionState.CLOSED, states1.poll(1, SECONDS));
        assertEquals(ConnectionState.CLOSED, states2.poll(1, SECONDS));
    }

    @Test
    void testRemovedListenerNotNotified() throws InterruptedException {
        var client = new SocketClient(mock(SocketProvider.class));
        var states = new LinkedBlockingQueue<ConnectionState>();
        BiConsumer<ConnectionState, Instant> listener = (s, ts) -> states.add(s);

        client.addStateChangeListener(listener);
        client.removeStateChangeListener(listener);

        client.close();

        assertNull(states.poll(100, MILLISECONDS));
    }

    @Test
    @Timeout(5)
    void testListenerExceptionDoesNotCrashClient() throws Exception {
        var server = new TestServer();
        Thread.startVirtualThread(server);
        var states = new LinkedBlockingQueue<ConnectionState>();
        var client = new SocketClient(plainProvider(server.getLocalPort()));

        client.addStateChangeListener(
                (s, ts) -> {
                    throw new RuntimeException("boom");
                });
        client.addStateChangeListener((s, ts) -> states.add(s));

        try (server;
                client) {
            client.start();

            assertEquals(ConnectionState.CONNECTING, states.poll(2, SECONDS));
            assertEquals(ConnectionState.CONNECTED, states.poll(2, SECONDS));
        }
    }

    @Test
    void testStateChangeListenerReceivesTimestamp() throws InterruptedException {
        var client = new SocketClient(mock(SocketProvider.class));
        var timestamps = new LinkedBlockingQueue<Instant>();
        Instant before = Instant.now();

        client.addStateChangeListener((s, ts) -> timestamps.add(ts));
        client.close();

        Instant ts = timestamps.poll(1, SECONDS);
        Instant after = Instant.now();
        assertNotNull(ts);
        assertFalse(
                ts.isBefore(before), "timestamp should not be before the listener was registered");
        assertFalse(ts.isAfter(after), "timestamp should not be in the future");
    }

    @Test
    @Timeout(3)
    void testProbeReachableReturnsTrueWhenOpen() throws Exception {
        try (var serverSocket = new ServerSocket(0)) {
            var client = new SocketClient(plainProvider(serverSocket.getLocalPort()));
            try (client) {
                assertTrue(client.probeReachable(Duration.ofSeconds(1)));
            }
        }
    }

    @Test
    @Timeout(3)
    void testProbeReachableReturnsFalseWhenClosed() throws Exception {
        int port;
        try (var serverSocket = new ServerSocket(0)) {
            port = serverSocket.getLocalPort();
        }
        var client = new SocketClient(plainProvider(port));
        try (client) {
            assertFalse(client.probeReachable(Duration.ofMillis(500)));
        }
    }
}
