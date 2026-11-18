package io.sapient.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.sapient.testing.Async;
import io.sapient.testing.FakeFusionNode;
import io.sapient.testing.Held;
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

/**
 * A publish must throw by its deadline even when the write is stuck. The publish must not close the
 * connection that every node shares, and must not break the framing.
 */
@Execution(ExecutionMode.CONCURRENT)
class SocketClientPublishTimeoutTest {

    /** NETCAT every 30 s, so no check fires during a test and no ping reaches the socket. */
    private static final HealthCheckConfig QUIET =
            new HealthCheckConfig(
                    HealthCheckType.NETCAT, Duration.ofSeconds(30), Duration.ofMillis(100), 1);

    private static final SapientMessage FIRST = msg("first");
    private static final SapientMessage SECOND = msg("second");

    private final FakeFusionNode server = new FakeFusionNode();
    private final BlockingQueue<ConnectionState> states = new LinkedBlockingQueue<>();

    @AfterEach
    void close() {
        server.close();
    }

    @Test
    void publishGivesUpByItsDeadlineWhileTheWriteIsStuck() throws Exception {
        try (SocketClient client = client(QUIET)) {
            connect(client);
            Held write = server.stopReading();

            // the server stops reading halfway through FIRST; publish must still throw at its
            // 200ms deadline. The bound is real time on purpose: the deadline is what is tested.
            // On a loaded machine a pause can use up the deadline before the write starts. Such
            // a publish sends nothing and leaves the stall in place, so the test tries again.
            do {
                assertTimeoutPreemptively(
                        Duration.ofSeconds(2),
                        () -> {
                            assertThrows(
                                    TimeoutException.class,
                                    () -> client.publish(FIRST, Duration.ofMillis(200)));
                        });
            } while (!write.hasArrived());

            // the connection is shared by every node, so one caller's deadline must not drop it
            assertEquals(0, server.disconnects());
            assertEquals(ConnectionState.CONNECTED, client.getState());

            // once the server reads again, the rest of FIRST goes out before SECOND starts
            write.release();
            client.publish(SECOND, Duration.ofSeconds(2));
            assertEquals(FIRST, server.next());
            assertEquals(SECOND, server.next());
        }
    }

    @Test
    void interruptingAStuckPublisherLeavesTheConnectionOpen() throws Exception {
        try (SocketClient client = client(QUIET)) {
            connect(client);
            Held write = server.stopReading();
            // node threads are virtual, and NodeWrapper.close() interrupts them to stop a node
            var publisher = Async.run(() -> client.publish(FIRST, Duration.ofSeconds(30)));
            write.awaitArrival();

            publisher.interrupt();

            // stopping one node must not close the connection the other nodes share
            assertInstanceOf(InterruptedException.class, publisher.joinFailure());
            assertEquals(0, server.disconnects());
            assertEquals(ConnectionState.CONNECTED, client.getState());

            // FIRST still goes out whole, and SECOND follows FIRST intact
            write.release();
            client.publish(SECOND, Duration.ofSeconds(2));
            assertEquals(FIRST, server.next());
            assertEquals(SECOND, server.next());
        }
    }

    @Test
    void stuckKeepaliveWriteStillDropsTheDeadConnection() throws Exception {
        // a ping every 100ms, two failed checks in a row drop the connection
        var echo =
                new HealthCheckConfig(
                        HealthCheckType.ECHO, Duration.ofMillis(100), Duration.ofMillis(100), 2);
        try (SocketClient client = client(echo)) {
            Held ping = server.stopReading();
            connect(client);

            // the server stopped reading, so the first ping gets stuck inside write()
            ping.awaitArrival();

            // the monitor must keep counting failed checks and drop the connection
            assertEquals(ConnectionState.DISCONNECTED, states.take());
        }
    }

    private SocketClient client(HealthCheckConfig health) {
        // a short reconnect delay: on a loaded machine the probe before a connect can miss its
        // 100ms timeout, and the client waits this long before it tries again. A dropped
        // connection shows in server.disconnects() whether or not the client comes back.
        var client = new SocketClient(server.sockets(), health, Duration.ofMillis(10));
        client.addStateChangeListener((state, at) -> states.add(state));
        return client;
    }

    private void connect(SocketClient client) throws InterruptedException {
        client.start();
        assertEquals(ConnectionState.CONNECTING, states.take());
        assertEquals(ConnectionState.CONNECTED, states.take());
    }

    private static SapientMessage msg(String mode) {
        return SapientMessage.newBuilder()
                .setStatusReport(StatusReport.newBuilder().setMode(mode).build())
                .build();
    }
}
