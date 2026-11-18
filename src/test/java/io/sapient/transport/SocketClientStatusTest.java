package io.sapient.transport;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import io.sapient.testing.FakeFusionNode;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

@Execution(ExecutionMode.CONCURRENT)
class SocketClientStatusTest {

    @Test
    void testInitialStateIsDisconnected() {
        var client = new SocketClient(mock(ISocketProvider.class));
        assertEquals(ConnectionState.DISCONNECTED, client.getState());
        assertFalse(client.isConnected());
        client.close();
    }

    @Test
    void testIsConnectedOnlyWhenStateIsConnected() {
        var client = new SocketClient(mock(ISocketProvider.class));
        assertFalse(client.isConnected()); // DISCONNECTED initially
        client.close();
        assertFalse(client.isConnected()); // CLOSED after close
    }

    @Test
    void testStateIsClosedAfterClientClose() throws InterruptedException {
        var client = new SocketClient(mock(ISocketProvider.class));
        var states = new LinkedBlockingQueue<ConnectionState>();
        client.addStateChangeListener((s, ts) -> states.add(s));

        client.close();

        assertEquals(ConnectionState.CLOSED, client.getState());
        assertEquals(ConnectionState.CLOSED, states.poll(1, SECONDS));
    }

    @Test
    void testStateTransitionsOnConnect() throws Exception {
        try (var server = new FakeFusionNode();
                var client = new SocketClient(server.sockets())) {
            var states = new LinkedBlockingQueue<ConnectionState>();
            client.addStateChangeListener((s, ts) -> states.add(s));

            client.start();

            assertEquals(ConnectionState.CONNECTING, states.take());
            assertEquals(ConnectionState.CONNECTED, states.take());
            assertTrue(client.isConnected());
        }
    }

    @Test
    void testStateBecomesDisconnectedAfterServerClose() throws Exception {
        try (var server = new FakeFusionNode();
                var client = new SocketClient(server.sockets())) {
            var states = new LinkedBlockingQueue<ConnectionState>();
            client.addStateChangeListener((s, ts) -> states.add(s));
            client.start();
            assertEquals(ConnectionState.CONNECTING, states.take());
            assertEquals(ConnectionState.CONNECTED, states.take());

            server.drop();

            assertEquals(ConnectionState.DISCONNECTED, states.take());
        }
    }

    @Test
    void testMultipleListenersAllNotified() throws InterruptedException {
        var client = new SocketClient(mock(ISocketProvider.class));
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
        var client = new SocketClient(mock(ISocketProvider.class));
        var states = new LinkedBlockingQueue<ConnectionState>();
        BiConsumer<ConnectionState, Instant> listener = (s, ts) -> states.add(s);

        client.addStateChangeListener(listener);
        client.removeStateChangeListener(listener);

        client.close();

        assertNull(states.poll(100, MILLISECONDS));
    }

    @Test
    void testListenerExceptionDoesNotCrashClient() throws Exception {
        try (var server = new FakeFusionNode();
                var client = new SocketClient(server.sockets())) {
            var states = new LinkedBlockingQueue<ConnectionState>();
            client.addStateChangeListener(
                    (s, ts) -> {
                        throw new RuntimeException("boom");
                    });
            client.addStateChangeListener((s, ts) -> states.add(s));

            client.start();

            assertEquals(ConnectionState.CONNECTING, states.take());
            assertEquals(ConnectionState.CONNECTED, states.take());
        }
    }

    @Test
    void testStateChangeListenerReceivesTimestamp() throws InterruptedException {
        var client = new SocketClient(mock(ISocketProvider.class));
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
    void testProbeReachableReturnsTrueWhenOpen() {
        try (var server = new FakeFusionNode();
                var client = new SocketClient(server.sockets())) {
            assertTrue(client.probeReachable(Duration.ofSeconds(1)));
        }
    }

    @Test
    void testProbeReachableReturnsFalseWhenClosed() {
        try (var server = new FakeFusionNode();
                var client = new SocketClient(server.sockets())) {
            server.goUnreachable();

            assertFalse(client.probeReachable(Duration.ofMillis(500)));
        }
    }
}
