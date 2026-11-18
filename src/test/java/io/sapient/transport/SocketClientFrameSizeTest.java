package io.sapient.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.sapient.testing.FakeFusionNode;
import io.sapient.transport.health.HealthCheckConfig;
import io.sapient.transport.health.HealthCheckType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import uk.gov.dstl.sapientmsg.bsiflex335v2.SapientMessage;
import uk.gov.dstl.sapientmsg.bsiflex335v2.StatusReport;

/** A frame the client cannot read costs one connection, not the client. */
@Execution(ExecutionMode.CONCURRENT)
class SocketClientFrameSizeTest {

    /** NETCAT every 30 s, so no check fires during a test. */
    private static final HealthCheckConfig QUIET =
            new HealthCheckConfig(
                    HealthCheckType.NETCAT, Duration.ofSeconds(30), Duration.ofMillis(100), 1);

    private final FakeFusionNode server = new FakeFusionNode();
    private final BlockingQueue<SapientMessage> received = new LinkedBlockingQueue<>();

    @AfterEach
    void close() {
        server.close();
    }

    @Test
    void testNegativePrefixDropsConnectionAndReconnects() throws Exception {
        try (var client = startedClient(server.sockets())) {
            server.awaitConnects(1);

            // 0xFFFFFFFF is -1 as a signed int. Before the guard this threw
            // NegativeArraySizeException, which escaped runLoop and killed the client for good.
            server.sendRawPrefix(0xFFFFFFFF);

            server.awaitConnects(2);
            server.send(msg("alive"));
            assertEquals(msg("alive"), received.take());
        }
    }

    @Test
    void testOversizedPrefixDropsConnectionAndReconnects() throws Exception {
        try (var client = startedClient(server.sockets())) {
            server.awaitConnects(1);

            server.sendRawPrefix(SocketClient.DEFAULT_MAX_FRAME_SIZE + 1);

            server.awaitConnects(2);
            server.send(msg("alive"));
            assertEquals(msg("alive"), received.take());
        }
    }

    @Test
    void testEmptyFrameStillDelivered() throws Exception {
        // locks the no-change promise: a zero-length prefix is a valid empty SapientMessage
        // and must keep working. A later plan relies on this staying true.
        try (var client = startedClient(server.sockets())) {
            server.awaitConnects(1);

            server.sendRawPrefix(0);

            assertEquals(SapientMessage.getDefaultInstance(), received.take());
        }
    }

    @Test
    void testUncheckedExceptionFromReadPathDoesNotKillRunLoop() throws Exception {
        // the first connection's reads blow up with an unchecked exception; the fusion node
        // supplies the connection after it
        ISocketProvider sockets = server.sockets();
        ISocketProvider provider = mock(ISocketProvider.class, delegatesTo(sockets));
        Socket broken = uncheckedFailingSocket();
        doReturn(broken).doAnswer(call -> sockets.get()).when(provider).get();

        try (var client = startedClient(provider)) {
            server.awaitConnects(1);

            server.send(msg("alive"));

            assertEquals(msg("alive"), received.take());
        }
    }

    private SocketClient startedClient(ISocketProvider provider) {
        var client = new SocketClient(provider, QUIET, Duration.ofMillis(10));
        client.subscribe(received::add);
        client.start();
        return client;
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

    private static SapientMessage msg(String mode) {
        return SapientMessage.newBuilder()
                .setStatusReport(StatusReport.newBuilder().setMode(mode).build())
                .build();
    }
}
