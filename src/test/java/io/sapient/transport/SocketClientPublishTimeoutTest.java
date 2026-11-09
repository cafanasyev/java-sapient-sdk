package io.sapient.transport;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.protobuf.InvalidProtocolBufferException;
import io.sapient.transport.health.HealthCheckConfig;
import io.sapient.transport.health.HealthCheckType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.stubbing.Answer;
import uk.gov.dstl.sapientmsg.bsiflex335v2.SapientMessage;
import uk.gov.dstl.sapientmsg.bsiflex335v2.StatusReport;

/**
 * A publish must throw by its deadline even when the write is stuck. The publish must not close the
 * connection that every node shares, and must not break the framing.
 */
@Execution(ExecutionMode.CONCURRENT)
class SocketClientPublishTimeoutTest {

    /** Upper bound for waiting on an asynchronous event; see NodeDispatcherTest.AWAIT_MS. */
    private static final long AWAIT_MS = 5000;

    /** NETCAT at a 30s interval never fires during a test, so no ping reaches the socket. */
    private static final HealthCheckConfig QUIET =
            new HealthCheckConfig(
                    HealthCheckType.NETCAT, Duration.ofSeconds(30), Duration.ofMillis(100), 1);

    private static final SapientMessage FIRST = msg("first");
    private static final SapientMessage SECOND = msg("second");

    /**
     * Mocked socket whose peer stopped reading. A write puts the first half of its bytes on the
     * wire, then blocks until the test calls {@link #resume()}. A real write blocks the same way
     * when the send buffer is full. {@link #wire} holds the bytes in the order the peer reads them.
     */
    private static final class StalledPeer {
        final Socket socket = mock(Socket.class);
        final ISocketProvider provider = mock(ISocketProvider.class);

        /** Counted down once a write is stuck halfway through its frame. */
        final CountDownLatch writeStuck = new CountDownLatch(1);

        private final ByteArrayOutputStream wire = new ByteArrayOutputStream();
        private final CountDownLatch resumed = new CountDownLatch(1);
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final PipedOutputStream peerOut = new PipedOutputStream();

        StalledPeer() throws IOException {
            OutputStream out = mock(OutputStream.class);
            Answer<Void> stallingWrite =
                    inv -> {
                        byte[] frame = inv.getArgument(0);
                        int half = frame.length / 2;
                        wire.write(frame, 0, half);
                        writeStuck.countDown();
                        try {
                            resumed.await();
                        } catch (InterruptedException e) {
                            // what the JDK does to a virtual thread interrupted inside a
                            // socket write: it closes the socket and keeps the interrupt set
                            socket.close();
                            Thread.currentThread().interrupt();
                            throw new SocketException("Closed by interrupt");
                        }
                        if (closed.get()) {
                            throw new SocketException("Socket closed");
                        }
                        wire.write(frame, half, frame.length - half);
                        return null;
                    };
            doAnswer(stallingWrite).when(out).write(any(byte[].class));

            // closing the socket ends the pending read and fails the stuck write
            Answer<Void> closeAnswer =
                    inv -> {
                        closed.set(true);
                        peerOut.close();
                        resumed.countDown();
                        return null;
                    };
            doAnswer(closeAnswer).when(socket).close();
            when(socket.getInputStream()).thenReturn(new PipedInputStream(peerOut));
            when(socket.getOutputStream()).thenReturn(out);
            when(socket.isConnected()).thenReturn(true);
            when(socket.isClosed()).thenAnswer(inv -> closed.get());

            // one connection only, so a dropped connection stays DISCONNECTED
            when(provider.get()).thenReturn(socket).thenThrow(new IOException("no reconnect"));
            // the NETCAT check builds its probe from the provider address, so the address must
            // not be null. Nothing listens there, and at a 30s interval the check never fires.
            when(provider.host()).thenReturn("localhost");
            when(provider.port()).thenReturn(1);
        }

        /** The peer reads again: the stuck write finishes and later writes go straight through. */
        void resume() {
            resumed.countDown();
        }

        /** Splits the wire into frames by their length prefix, failing on a cut-short frame. */
        List<SapientMessage> frames() throws InvalidProtocolBufferException {
            ByteBuffer buf = ByteBuffer.wrap(wire.toByteArray()).order(ByteOrder.LITTLE_ENDIAN);
            List<SapientMessage> frames = new ArrayList<>();
            while (buf.hasRemaining()) {
                assertTrue(buf.remaining() >= 4, "length prefix cut short");
                int len = buf.getInt();
                assertTrue(buf.remaining() >= len, "frame body cut short");
                byte[] body = new byte[len];
                buf.get(body);
                frames.add(SapientMessage.parseFrom(body));
            }
            return frames;
        }
    }

    private static SapientMessage msg(String mode) {
        return SapientMessage.newBuilder()
                .setStatusReport(StatusReport.newBuilder().setMode(mode).build())
                .build();
    }

    private static SocketClient client(StalledPeer peer, HealthCheckConfig health) {
        // a 30s reconnect delay keeps a dropped connection dropped for the rest of the test
        SocketClient client = spy(new SocketClient(peer.provider, health, Duration.ofSeconds(30)));
        // the mocked socket binds no listener, so a real TCP probe would have nothing to reach
        doReturn(true).when(client).probeReachable(any(Duration.class));
        return client;
    }

    private static void awaitConnected(BlockingQueue<ConnectionState> states)
            throws InterruptedException {
        assertEquals(ConnectionState.CONNECTING, states.poll(AWAIT_MS, MILLISECONDS));
        assertEquals(ConnectionState.CONNECTED, states.poll(AWAIT_MS, MILLISECONDS));
    }

    @Test
    @Timeout(20)
    void publishGivesUpByItsDeadlineWhileTheWriteIsStuck() throws Exception {
        StalledPeer peer = new StalledPeer();
        var states = new LinkedBlockingQueue<ConnectionState>();
        try (SocketClient client = client(peer, QUIET)) {
            client.addStateChangeListener((s, ts) -> states.add(s));
            client.start();
            awaitConnected(states);

            // the peer stops reading halfway through FIRST; publish must still throw at its
            // 200ms deadline
            assertTimeoutPreemptively(
                    Duration.ofSeconds(2),
                    () -> {
                        assertThrows(
                                TimeoutException.class,
                                () -> client.publish(FIRST, Duration.ofMillis(200)));
                    });
            assertTrue(peer.writeStuck.await(AWAIT_MS, MILLISECONDS));

            // the connection is shared by every node, so one caller's deadline must not drop it
            verify(peer.socket, never()).close();
            assertEquals(ConnectionState.CONNECTED, client.getState());

            // once the peer reads again, the rest of FIRST goes out before SECOND starts
            peer.resume();
            client.publish(SECOND, Duration.ofSeconds(2));
            assertEquals(List.of(FIRST, SECOND), peer.frames());
        }
    }

    @Test
    @Timeout(20)
    void interruptingAStuckPublisherLeavesTheConnectionOpen() throws Exception {
        StalledPeer peer = new StalledPeer();
        var states = new LinkedBlockingQueue<ConnectionState>();
        try (SocketClient client = client(peer, QUIET)) {
            client.addStateChangeListener((s, ts) -> states.add(s));
            client.start();
            awaitConnected(states);

            var outcome = new CompletableFuture<Exception>();
            Runnable publishFirst =
                    () -> {
                        try {
                            client.publish(FIRST, Duration.ofSeconds(30));
                            outcome.complete(null);
                        } catch (TimeoutException | InterruptedException e) {
                            outcome.complete(e);
                        }
                    };
            // node threads are virtual, and NodeWrapper.close() interrupts them to stop a node
            Thread publisher = Thread.ofVirtual().start(publishFirst);
            assertTrue(peer.writeStuck.await(AWAIT_MS, MILLISECONDS));

            publisher.interrupt();

            // stopping one node must not close the connection the other nodes share
            verify(peer.socket, after(300).never()).close();
            assertInstanceOf(InterruptedException.class, outcome.get(AWAIT_MS, MILLISECONDS));
            assertEquals(ConnectionState.CONNECTED, client.getState());

            // FIRST still goes out whole, and SECOND follows FIRST intact
            peer.resume();
            client.publish(SECOND, Duration.ofSeconds(2));
            assertEquals(List.of(FIRST, SECOND), peer.frames());
        }
    }

    @Test
    @Timeout(20)
    void stuckKeepaliveWriteStillDropsTheDeadConnection() throws Exception {
        StalledPeer peer = new StalledPeer();
        var states = new LinkedBlockingQueue<ConnectionState>();
        // a ping every 100ms, two failed checks in a row drop the connection
        var echo =
                new HealthCheckConfig(
                        HealthCheckType.ECHO, Duration.ofMillis(100), Duration.ofMillis(100), 2);
        try (SocketClient client = client(peer, echo)) {
            client.addStateChangeListener((s, ts) -> states.add(s));
            client.start();
            awaitConnected(states);

            // the peer stopped reading, so the first ping write gets stuck inside write()
            assertTrue(peer.writeStuck.await(AWAIT_MS, MILLISECONDS));

            // the monitor must keep counting failed checks and drop the connection
            assertEquals(ConnectionState.DISCONNECTED, states.poll(AWAIT_MS, MILLISECONDS));
        }
    }
}
