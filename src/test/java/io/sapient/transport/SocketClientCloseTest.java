package io.sapient.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.sapient.testing.FakeFusionNode;
import io.sapient.transport.health.HealthCheckConfig;
import io.sapient.transport.health.HealthCheckType;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import uk.gov.dstl.sapientmsg.bsiflex335v2.SapientMessage;
import uk.gov.dstl.sapientmsg.bsiflex335v2.StatusReport;

/** Shutdown behaviour of {@link SocketClient}: socket closing, restart, and stale connections. */
@Execution(ExecutionMode.CONCURRENT)
class SocketClientCloseTest {

    private static final SapientMessage MESSAGE =
            SapientMessage.newBuilder().setStatusReport(StatusReport.getDefaultInstance()).build();

    /** NETCAT every 30 s, so no check fires during a test. */
    private static final HealthCheckConfig QUIET =
            new HealthCheckConfig(
                    HealthCheckType.NETCAT, Duration.ofSeconds(30), Duration.ofMillis(100), 1);

    private final FakeFusionNode server = new FakeFusionNode();
    private final BlockingQueue<ConnectionState> states = new LinkedBlockingQueue<>();

    @AfterEach
    void close() {
        server.close();
    }

    @Test
    void socketClosedExactlyOnceWhenClientClosed() throws Exception {
        SocketClient client = client(Duration.ofMillis(10));
        client.start();
        expectStates(ConnectionState.CONNECTING, ConnectionState.CONNECTED);

        client.close();

        server.awaitDisconnects(1);
        // the run loop must not close the socket a second time on its way out. A second close
        // would run on a thread of its own, so this waits in real time to give it the chance.
        Thread.sleep(500);
        assertEquals(1, server.disconnects());
    }

    @Test
    void startAfterCloseLeavesNoSecondRunLoop() throws Exception {
        SocketClient client = client(Duration.ofMillis(500));
        // an unreachable server parks the run loop in its reconnect backoff
        server.goUnreachable();
        client.start();
        Thread.sleep(200); // real time: the loop reaches its backoff; nothing signals that

        client.close();

        // a client that was closed mid-backoff must not leave a run loop behind that
        // wakes up and connects alongside the loop started here
        server.goReachable();
        client.start();
        server.awaitConnects(1);
        Thread.sleep(1000); // real time: a leftover loop would connect after its 500 ms backoff
        assertEquals(1, server.connects());

        client.close();
    }

    @Test
    void publishRefusesConnectionTornDownByReadLoop() throws Exception {
        try (SocketClient client = client(Duration.ofMillis(10))) {
            client.start();
            expectStates(ConnectionState.CONNECTING, ConnectionState.CONNECTED);
            server.goUnreachable(); // the first connection is the only one this client gets

            server.drop(); // the server closed the connection
            expectStates(ConnectionState.DISCONNECTED);

            assertThrows(
                    TimeoutException.class, () -> client.publish(MESSAGE, Duration.ofMillis(300)));
            // what the client writes after a drop still arrives, so this sees any such write
            server.expectNothingMore();
        }
    }

    private SocketClient client(Duration reconnectDelay) {
        var client = new SocketClient(server.sockets(), QUIET, reconnectDelay);
        client.addStateChangeListener((state, at) -> states.add(state));
        return client;
    }

    private void expectStates(ConnectionState... expected) throws InterruptedException {
        for (ConnectionState state : expected) {
            assertEquals(state, states.take());
        }
    }
}
