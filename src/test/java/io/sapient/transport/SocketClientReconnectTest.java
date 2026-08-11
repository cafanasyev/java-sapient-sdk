package io.sapient.transport;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
class SocketClientReconnectTest {

    private final ArrayBlockingQueue<SapientMessage> received = new ArrayBlockingQueue<>(8);
    private final ISocketProvider supplier = mock(ISocketProvider.class);
    private final SocketClient client = spy(new SocketClient(supplier));

    @BeforeEach
    void setUp() {
        client.subscribe(received::offer);
        // bypass probe-before-connect; these tests use mocked sockets and don't bind
        // a real listener, so a real TCP probe has nothing to connect to
        doReturn(true).when(client).probeReachable(any(Duration.class));
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
    @Timeout(value = 5, unit = SECONDS)
    void testReconnectAfterConnectionLost() throws Exception {
        var pipe1 = new MockPipe();
        var pipe2 = new MockPipe();
        Socket s1 = pipe1.socket(), s2 = pipe2.socket();
        when(supplier.get()).thenReturn(s1, s2);

        client.start();

        pipe1.send(msg("msg1"));
        assertEquals(msg("msg1"), received.poll(2, SECONDS));

        client.publish(msg("pub1"), Duration.ofSeconds(2));
        assertEquals(msg("pub1"), pipe1.captured());

        // simulate server disconnect → EOF on read → triggers reconnect
        pipe1.serverClose();

        pipe2.send(msg("msg2"));
        assertEquals(msg("msg2"), received.poll(3, SECONDS));

        verify(supplier, atLeast(2)).get();

        client.publish(msg("pub2"), Duration.ofSeconds(2));
        assertEquals(msg("pub2"), pipe2.captured());
    }

    @Test
    @Timeout(value = 5, unit = SECONDS)
    void testReconnectAfterConnectionFailure() throws Exception {
        Socket failSocket = mockFailingSocket();
        var pipe = new MockPipe();
        Socket goodSocket = pipe.socket();
        when(supplier.get()).thenReturn(failSocket, goodSocket);

        client.start();

        pipe.send(msg("hello"));
        assertEquals(msg("hello"), received.poll(3, SECONDS));

        verify(supplier, atLeast(2)).get();

        client.publish(msg("world"), Duration.ofSeconds(2));
        assertEquals(msg("world"), pipe.captured());
    }

    private static Socket mockFailingSocket() throws IOException {
        Socket socket = mock(Socket.class);
        var failIn = mock(java.io.InputStream.class);
        when(failIn.read(any(byte[].class), anyInt(), anyInt()))
                .thenThrow(new IOException("Connection reset"));
        when(socket.getInputStream()).thenReturn(failIn);
        when(socket.getOutputStream()).thenReturn(new ByteArrayOutputStream());
        when(socket.isConnected()).thenReturn(true);
        return socket;
    }
}
