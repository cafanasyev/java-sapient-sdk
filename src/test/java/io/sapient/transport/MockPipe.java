package io.sapient.transport;

import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.protobuf.InvalidProtocolBufferException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import org.mockito.stubbing.Answer;
import uk.gov.dstl.sapientmsg.bsiflex335v2.SapientMessage;

/**
 * Fake server side of a socket. The test writes into {@code serverOut} and the client reads the
 * same bytes from {@code clientIn}, which the pipe feeds. Anything the client writes lands in
 * {@code clientOut}, where the test can read it back. Lets a test drive a connection without
 * binding a port.
 */
class MockPipe {
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
        Answer<Void> closeAnswer =
                inv -> {
                    connected.set(false);
                    serverOut.close();
                    return null;
                };
        doAnswer(closeAnswer).when(socket).close();
        return socket;
    }

    void send(SapientMessage msg) throws IOException {
        byte[] data = msg.toByteArray();
        serverOut.write(
                ByteBuffer.allocate(4 + data.length)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(data.length)
                        .put(data)
                        .array());
        serverOut.flush();
    }

    /**
     * Writes only a 4-byte little-endian length prefix and no body. Lets a test drive the client
     * with a length it would never produce itself.
     */
    void sendRawPrefix(int len) throws IOException {
        serverOut.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(len).array());
        serverOut.flush();
    }

    void serverClose() throws IOException {
        serverOut.close();
    }

    /**
     * Reads back the first frame the client wrote. Parses from the start of the buffer every time,
     * so a second call after a second publish returns the first message again. Use one pipe per
     * message you want to check.
     */
    SapientMessage captured() throws InvalidProtocolBufferException {
        byte[] bytes = clientOut.toByteArray();
        int len = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
        return SapientMessage.parseFrom(Arrays.copyOfRange(bytes, 4, 4 + len));
    }
}
