package io.sapient.transport;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

@Execution(ExecutionMode.CONCURRENT)
class SocketClientReconnectTest {

    private final ArrayBlockingQueue<String> received = new ArrayBlockingQueue<>(8);
    private final SocketProvider supplier = mock();
    private final SocketClient client = new SocketClient(supplier);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @BeforeEach
    void setUp() {
        client.subscribe(
                bb -> {
                    byte[] data = new byte[bb.remaining()];
                    bb.get(data);
                    received.offer(new String(data));
                });
    }

    @AfterEach
    void tearDown() {
        client.close();
        executor.shutdownNow();
    }

    @Test
    @Timeout(value = 5, unit = SECONDS)
    void testReconnectAfterConnectionLost() throws Exception {
        var pipe1 = new MockPipe();
        var pipe2 = new MockPipe();
        Socket s1 = pipe1.socket(), s2 = pipe2.socket();
        when(supplier.get()).thenReturn(s1, s2);

        executor.execute(client);

        pipe1.send("msg1");
        assertEquals("msg1", received.poll(2, SECONDS));

        client.publish(ByteBuffer.wrap("pub1".getBytes()), Duration.ofSeconds(2));
        assertEquals("pub1", pipe1.captured());

        // simulate server disconnect → EOF on read → triggers reconnect
        pipe1.serverClose();

        pipe2.send("msg2");
        assertEquals("msg2", received.poll(3, SECONDS));

        verify(supplier, atLeast(2)).get();

        client.publish(ByteBuffer.wrap("pub2".getBytes()), Duration.ofSeconds(2));
        assertEquals("pub2", pipe2.captured());
    }

    @Test
    @Timeout(value = 5, unit = SECONDS)
    void testReconnectAfterConnectionFailure() throws Exception {
        Socket failSocket = mockFailingSocket();
        var pipe = new MockPipe();
        Socket goodSocket = pipe.socket();
        when(supplier.get()).thenReturn(failSocket, goodSocket);

        executor.execute(client);

        pipe.send("hello");
        assertEquals("hello", received.poll(3, SECONDS));

        verify(supplier, atLeast(2)).get();

        client.publish(ByteBuffer.wrap("world".getBytes()), Duration.ofSeconds(2));
        assertEquals("world", pipe.captured());
    }

    private static Socket mockFailingSocket() throws IOException {
        Socket socket = mock(Socket.class);
        var failIn = mock(java.io.InputStream.class);
        when(failIn.read(any(byte[].class))).thenThrow(new IOException("Connection reset"));
        when(socket.getInputStream()).thenReturn(failIn);
        when(socket.getOutputStream()).thenReturn(new ByteArrayOutputStream());
        when(socket.isConnected()).thenReturn(true);
        return socket;
    }

    private static class MockPipe {
        private final PipedOutputStream serverOut = new PipedOutputStream();
        private final PipedInputStream clientIn = new PipedInputStream(serverOut);
        private final ByteArrayOutputStream clientOut = new ByteArrayOutputStream();
        private final AtomicBoolean connected = new AtomicBoolean(true);

        MockPipe() throws IOException {}

        Socket socket() throws IOException {
            Socket socket = mock(Socket.class);
            when(socket.getInputStream()).thenReturn(clientIn);
            when(socket.getOutputStream()).thenReturn(clientOut);
            when(socket.isConnected()).thenAnswer(inv -> connected.get());
            doAnswer(
                            inv -> {
                                connected.set(false);
                                serverOut.close();
                                return null;
                            })
                    .when(socket)
                    .close();
            return socket;
        }

        void send(String msg) throws IOException {
            serverOut.write(msg.getBytes());
            serverOut.flush();
        }

        void serverClose() throws IOException {
            serverOut.close();
        }

        String captured() {
            return clientOut.toString();
        }
    }
}
