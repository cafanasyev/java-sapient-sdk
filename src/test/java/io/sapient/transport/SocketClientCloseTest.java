package io.sapient.transport;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.stubbing.Answer;
import uk.gov.dstl.sapientmsg.bsiflex335v2.SapientMessage;
import uk.gov.dstl.sapientmsg.bsiflex335v2.StatusReport;

/** Shutdown behaviour of {@link SocketClient}: socket closing, restart, and stale connections. */
@Execution(ExecutionMode.CONCURRENT)
class SocketClientCloseTest {

    /** Upper bound for waiting on an asynchronous event; see NodeDispatcherTest.AWAIT_MS. */
    private static final long AWAIT_MS = 5000;

    private static final SapientMessage MESSAGE =
            SapientMessage.newBuilder().setStatusReport(StatusReport.getDefaultInstance()).build();

    /**
     * Blocks in {@code read()} until the peer sends EOF or the socket is closed, like a real one.
     */
    private static final class BlockingInputStream extends InputStream {
        private final CountDownLatch eof = new CountDownLatch(1);

        @Override
        public int read() throws IOException {
            try {
                eof.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
            return -1;
        }

        void signalEof() {
            eof.countDown();
        }
    }

    private record Endpoint(
            ISocketProvider provider,
            Socket socket,
            BlockingInputStream in,
            ByteArrayOutputStream out) {}

    private static Endpoint endpoint() throws IOException {
        BlockingInputStream in = new BlockingInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Socket socket = mock(Socket.class);
        when(socket.getInputStream()).thenReturn(in);
        when(socket.getOutputStream()).thenReturn(out);
        when(socket.isConnected()).thenReturn(true);
        // closing a real socket unblocks the reads pending on it
        Answer<Void> closeAnswer =
                inv -> {
                    in.signalEof();
                    return null;
                };
        doAnswer(closeAnswer).when(socket).close();

        ISocketProvider provider = mock(ISocketProvider.class);
        when(provider.get()).thenReturn(socket);
        return new Endpoint(provider, socket, in, out);
    }

    private static SocketClient reachableClient(ISocketProvider provider) {
        SocketClient client =
                spy(
                        new SocketClient(
                                provider,
                                Duration.ofMillis(100),
                                Duration.ofSeconds(2),
                                Duration.ofSeconds(30)));
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
    void socketClosedExactlyOnceWhenClientClosed() throws Exception {
        Endpoint ep = endpoint();
        var states = new LinkedBlockingQueue<ConnectionState>();
        SocketClient client = reachableClient(ep.provider());
        client.addStateChangeListener((s, ts) -> states.add(s));

        client.start();
        awaitConnected(states);

        client.close();

        // the run loop must not close the socket a second time on its way out
        verify(ep.socket(), timeout(AWAIT_MS)).close();
        Thread.sleep(500);
        verify(ep.socket(), times(1)).close();
    }

    @Test
    @Timeout(20)
    void startAfterCloseLeavesNoSecondRunLoop() throws Exception {
        Endpoint ep = endpoint();
        SocketClient client =
                spy(
                        new SocketClient(
                                ep.provider(),
                                Duration.ofMillis(100),
                                Duration.ofMillis(500),
                                Duration.ofSeconds(30)));
        // unreachable endpoint parks the run loop in its reconnect backoff
        doReturn(false).when(client).probeReachable(any(Duration.class));

        client.start();
        Thread.sleep(200);

        client.close();

        // a client that was closed mid-backoff must not leave a run loop behind that
        // wakes up and connects alongside the loop started here
        doReturn(true).when(client).probeReachable(any(Duration.class));
        client.start();

        verify(ep.provider(), timeout(AWAIT_MS)).get();
        Thread.sleep(1000);
        verify(ep.provider(), times(1)).get();

        client.close();
    }

    @Test
    @Timeout(20)
    void publishRefusesConnectionTornDownByReadLoop() throws Exception {
        Endpoint ep = endpoint();
        // the first connection is the only one this client ever gets
        when(ep.provider().get())
                .thenReturn(ep.socket())
                .thenThrow(new IOException("no reconnect"));
        var states = new LinkedBlockingQueue<ConnectionState>();
        SocketClient client = reachableClient(ep.provider());
        client.addStateChangeListener((s, ts) -> states.add(s));

        client.start();
        awaitConnected(states);

        ep.in().signalEof(); // peer closed the connection
        assertEquals(ConnectionState.DISCONNECTED, states.poll(AWAIT_MS, MILLISECONDS));

        assertThrows(TimeoutException.class, () -> client.publish(MESSAGE, Duration.ofMillis(300)));
        assertEquals(0, ep.out().size(), "must not write to a torn-down connection");

        client.close();
    }
}
