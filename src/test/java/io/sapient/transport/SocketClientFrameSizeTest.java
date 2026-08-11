package io.sapient.transport;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import uk.gov.dstl.sapientmsg.bsiflex335v2.SapientMessage;
import uk.gov.dstl.sapientmsg.bsiflex335v2.StatusReport;

@Execution(ExecutionMode.CONCURRENT)
class SocketClientFrameSizeTest {

    private final ArrayBlockingQueue<SapientMessage> received = new ArrayBlockingQueue<>(8);
    private final ISocketProvider supplier = mock(ISocketProvider.class);
    private final SocketClient client = spy(new SocketClient(supplier));

    @BeforeEach
    void setUp() {
        client.subscribe(received::offer);
        // these tests use mocked sockets and bind no listener, so a real TCP probe
        // would have nothing to connect to
        doReturn(true).when(client).probeReachable(any(Duration.class));
        // the default NETCAT health check builds a probe from the provider address, so it
        // must not be null. Nothing listens there, and at a 10s interval it never fires.
        when(supplier.host()).thenReturn("localhost");
        when(supplier.port()).thenReturn(1);
    }

    @AfterEach
    void tearDown() {
        client.close();
    }

    static SapientMessage msg(String mode) {
        return SapientMessage.newBuilder()
                .setStatusReport(StatusReport.newBuilder().setMode(mode).build())
                .build();
    }

    @Test
    @Timeout(value = 10, unit = SECONDS)
    void testNegativePrefixDropsConnectionAndReconnects() throws Exception {
        var bad = new MockPipe();
        var good = new MockPipe();
        // build the sockets before stubbing: MockPipe.socket() stubs its own mock, and
        // Mockito rejects a stubbing that starts inside an unfinished one
        Socket badSocket = bad.socket();
        Socket goodSocket = good.socket();
        when(supplier.get()).thenReturn(badSocket, goodSocket);

        client.start();

        // 0xFFFFFFFF is -1 as a signed int. Before the guard this threw
        // NegativeArraySizeException, which escaped runLoop and killed the client for good.
        bad.sendRawPrefix(0xFFFFFFFF);

        good.send(msg("alive"));
        assertEquals(msg("alive"), received.poll(6, SECONDS));
    }

    @Test
    @Timeout(value = 10, unit = SECONDS)
    void testOversizedPrefixDropsConnectionAndReconnects() throws Exception {
        var bad = new MockPipe();
        var good = new MockPipe();
        Socket badSocket = bad.socket();
        Socket goodSocket = good.socket();
        when(supplier.get()).thenReturn(badSocket, goodSocket);

        client.start();

        bad.sendRawPrefix(SocketClient.DEFAULT_MAX_FRAME_SIZE + 1);

        good.send(msg("alive"));
        assertEquals(msg("alive"), received.poll(6, SECONDS));
    }

    @Test
    @Timeout(value = 10, unit = SECONDS)
    void testEmptyFrameStillDelivered() throws Exception {
        // locks the no-change promise: a zero-length prefix is a valid empty SapientMessage
        // and must keep working. A later plan relies on this staying true.
        var pipe = new MockPipe();
        Socket socket = pipe.socket();
        when(supplier.get()).thenReturn(socket);

        client.start();

        pipe.sendRawPrefix(0);

        assertEquals(SapientMessage.getDefaultInstance(), received.poll(6, SECONDS));
    }

    @Test
    @Timeout(value = 10, unit = SECONDS)
    void testUncheckedExceptionFromReadPathDoesNotKillRunLoop() throws Exception {
        var good = new MockPipe();
        // both helpers stub their own mocks, so they must finish before the outer
        // when(...) starts — otherwise Mockito raises UnfinishedStubbingException
        Socket badSocket = uncheckedFailingSocket();
        Socket goodSocket = good.socket();
        when(supplier.get()).thenReturn(badSocket, goodSocket);

        client.start();

        good.send(msg("alive"));
        assertEquals(msg("alive"), received.poll(6, SECONDS));
    }

    /** A socket whose reads blow up with an unchecked exception rather than an IOException. */
    private static Socket uncheckedFailingSocket() throws IOException {
        Socket socket = mock(Socket.class);
        var failIn = mock(InputStream.class);
        when(failIn.read(any(byte[].class), anyInt(), anyInt()))
                .thenThrow(new IllegalStateException("boom"));
        when(socket.getInputStream()).thenReturn(failIn);
        when(socket.getOutputStream()).thenReturn(new ByteArrayOutputStream());
        when(socket.isConnected()).thenReturn(true);
        return socket;
    }
}
