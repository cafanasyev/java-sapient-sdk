package io.sapient.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.sapient.transport.ConnectionState;
import io.sapient.transport.ISocketProvider;
import io.sapient.transport.SocketClient;
import io.sapient.transport.health.HealthCheckConfig;
import io.sapient.transport.health.HealthCheckType;
import io.sapient.transport.health.KeepalivePrefixes;
import io.sapient.transport.health.NetcatHealthCheck;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import uk.gov.dstl.sapientmsg.bsiflex335v2.Registration;
import uk.gov.dstl.sapientmsg.bsiflex335v2.SapientMessage;
import uk.gov.dstl.sapientmsg.bsiflex335v2.StatusReport;

/** The socket attachment: frames both ways, keepalive answers, and the transport faults. */
@Execution(ExecutionMode.CONCURRENT)
class FakeFusionNodeSocketTest {

    private static final SapientMessage MESSAGE =
            SapientMessage.newBuilder()
                    .setStatusReport(StatusReport.newBuilder().setMode("hello"))
                    .build();

    /** NETCAT every 30 s, so no check fires during a test. */
    private static final HealthCheckConfig QUIET =
            new HealthCheckConfig(
                    HealthCheckType.NETCAT, Duration.ofSeconds(30), Duration.ofMillis(100), 1);

    private final FakeFusionNode server = new FakeFusionNode();
    private final SocketClient client =
            new SocketClient(server.sockets(), QUIET, Duration.ofMillis(10));
    private final BlockingQueue<SapientMessage> received = new LinkedBlockingQueue<>();
    private final BlockingQueue<ConnectionState> states = new LinkedBlockingQueue<>();

    @BeforeEach
    void listen() {
        client.subscribe(received::add);
        client.addStateChangeListener((state, at) -> states.add(state));
    }

    @AfterEach
    void close() {
        client.close();
        server.close();
    }

    @Test
    void framesReachTheServer() throws Exception {
        client.start();

        client.publish(MESSAGE, Duration.ofSeconds(5));

        assertEquals(MESSAGE, server.next());
    }

    @Test
    void serverFramesReachTheSubscriber() throws Exception {
        client.start();
        server.awaitConnects(1);

        server.send(MESSAGE);

        assertEquals(MESSAGE, received.take());
    }

    @Test
    void sendNeedsAnOpenConnection() {
        assertThrows(IllegalStateException.class, () -> server.send(MESSAGE));
    }

    @ParameterizedTest
    @EnumSource(names = {"ECHO", "PINGPONG"})
    void inBandKeepaliveIsAnswered(HealthCheckType type) throws Exception {
        KeepalivePrefixes prefixes = KeepalivePrefixes.of(type);
        try (var server = FakeFusionNode.builder().withKeepalive(type).build()) {
            Socket socket = server.sockets().get();

            socket.getOutputStream().write(prefix(prefixes.ping()));

            assertEquals(prefixes.pong(), readPrefix(socket.getInputStream()));
            server.expectNothingMore(); // a ping is not a message
        }
    }

    @Test
    void dropEndsTheConnection() throws Exception {
        client.start();
        expectStates(ConnectionState.CONNECTING, ConnectionState.CONNECTED);

        server.drop();

        expectStates(ConnectionState.DISCONNECTED);
    }

    @Test
    void whatTheClientWritesAfterADropStillArrives() throws Exception {
        Socket socket = server.sockets().get();
        var registration =
                SapientMessage.newBuilder()
                        .setNodeId("n1")
                        .setRegistration(Registration.getDefaultInstance())
                        .build();

        server.drop();
        socket.getOutputStream().write(frame(registration));

        assertEquals(registration, server.next());
        // the ack went out after the end, so the client reads EOF and nothing more
        InputStream in = socket.getInputStream();
        assertEquals(-1, in.read());
        assertEquals(-1, in.read());
    }

    @Test
    void stopReadingStallsTheWrite() throws Exception {
        client.start();
        Held write = server.stopReading();

        var publish = Async.run(() -> client.publish(MESSAGE, Duration.ofSeconds(5)));
        write.awaitArrival();
        server.expectNothingMore(); // half a frame is not a message yet
        write.release();
        publish.join();

        assertEquals(MESSAGE, server.next()); // the stuck frame arrives whole
    }

    @Test
    void closingTheSocketFailsAStuckWrite() throws Exception {
        Socket socket = server.sockets().get();
        Held write = server.stopReading();
        var writer = Async.run(() -> socket.getOutputStream().write(frame(MESSAGE)));
        write.awaitArrival();

        socket.close();

        assertInstanceOf(SocketException.class, writer.joinFailure());
        server.expectNothingMore(); // half a frame is not a message
    }

    @Test
    void interruptingAStuckWriteClosesTheSocket() throws Exception {
        Socket socket = server.sockets().get();
        Held write = server.stopReading();
        var writer = Async.run(() -> socket.getOutputStream().write(frame(MESSAGE)));
        write.awaitArrival();

        writer.interrupt();

        assertInstanceOf(SocketException.class, writer.joinFailure());
        assertTrue(socket.isClosed());
        assertEquals(1, server.disconnects());
    }

    @Test
    void refuseNextConnectionRefusesOnlyOne() throws Exception {
        ISocketProvider sockets = server.sockets();

        server.refuseNextConnection();

        assertThrows(ConnectException.class, sockets::get);
        assertNotNull(sockets.get());
    }

    @Test
    void goUnreachableFailsTheNetcatCheck() throws Exception {
        ISocketProvider sockets = server.sockets();
        var netcat = new NetcatHealthCheck(sockets.host(), sockets.port(), Duration.ofSeconds(1));
        assertTrue(netcat.check());

        server.goUnreachable();
        assertFalse(netcat.check());
        assertThrows(ConnectException.class, sockets::get);

        server.goReachable();
        assertTrue(netcat.check());
    }

    private void expectStates(ConnectionState... expected) throws InterruptedException {
        for (ConnectionState state : expected) {
            assertEquals(state, states.take());
        }
    }

    private static byte[] frame(SapientMessage message) {
        byte[] body = message.toByteArray();
        return ByteBuffer.allocate(4 + body.length)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(body.length)
                .put(body)
                .array();
    }

    private static byte[] prefix(int value) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
    }

    private static int readPrefix(InputStream in) throws IOException {
        return ByteBuffer.wrap(in.readNBytes(4)).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }
}
