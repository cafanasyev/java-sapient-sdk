package io.sapient.transport;

import com.google.protobuf.InvalidProtocolBufferException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import lombok.Getter;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Assertions;
import uk.gov.dstl.sapientmsg.bsiflex335v2.RegistrationAck;
import uk.gov.dstl.sapientmsg.bsiflex335v2.SapientMessage;

public class TestServer implements Runnable, AutoCloseable {
    private static final int AUTO_ALLOCATED_PORT = 0;

    public static final SapientMessage GREETING =
            SapientMessage.newBuilder()
                    .setRegistrationAck(RegistrationAck.newBuilder().setAcceptance(true).build())
                    .build();

    private final ServerSocket serverSocket;
    private final AtomicBoolean running = new AtomicBoolean(true);

    @Getter
    private final BlockingQueue<SapientMessage> clientMessages = new ArrayBlockingQueue<>(8);

    private Socket clientConnection;

    public TestServer() throws IOException {
        serverSocket = new ServerSocket(AUTO_ALLOCATED_PORT);
    }

    public TestServer(SSLContext sslContext) throws IOException {
        SSLServerSocket sslServerSocket =
                (SSLServerSocket)
                        sslContext.getServerSocketFactory().createServerSocket(AUTO_ALLOCATED_PORT);
        sslServerSocket.setNeedClientAuth(true);
        serverSocket = sslServerSocket;
    }

    @Override
    public void close() throws IOException {
        running.set(false);
        try {
            if (clientConnection != null && !clientConnection.isClosed()) {
                clientConnection.close();
            }
        } catch (IOException ignored) {
        }
        serverSocket.close();
    }

    public int getLocalPort() {
        return serverSocket.getLocalPort();
    }

    @Override
    @SneakyThrows
    public void run() {
        clientConnection = serverSocket.accept();
        OutputStream out = clientConnection.getOutputStream();
        InputStream in = clientConnection.getInputStream();

        writeFramed(out, GREETING.toByteArray());

        while (running.get()) {
            byte[] msg = readFramed(in);
            if (msg == null) {
                running.set(false);
                break;
            }

            try {
                SapientMessage message = SapientMessage.parseFrom(msg);
                boolean offer = clientMessages.offer(message, 3, TimeUnit.SECONDS);
                Assertions.assertTrue(offer, "Failed to insert client message to the queue");
            } catch (InvalidProtocolBufferException e) {
                Assertions.fail("Failed to parse SapientMessage from client: " + e.getMessage());
            }
        }

        clientConnection.close();
    }

    private static void writeFramed(OutputStream out, byte[] data) throws IOException {
        byte[] frame =
                ByteBuffer.allocate(4 + data.length)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(data.length)
                        .put(data)
                        .array();
        out.write(frame);
        out.flush();
    }

    private static byte[] readFramed(InputStream in) throws IOException {
        byte[] lenBuf = new byte[4];
        if (!readFully(in, lenBuf, 4)) {
            return null;
        }
        int len = ByteBuffer.wrap(lenBuf).order(ByteOrder.LITTLE_ENDIAN).getInt();
        byte[] buf = new byte[len];
        if (!readFully(in, buf, len)) {
            return null;
        }
        return buf;
    }

    private static boolean readFully(InputStream in, byte[] buf, int count) throws IOException {
        int total = 0;
        while (total < count) {
            int n = in.read(buf, total, count - total);
            if (n < 0) {
                return false;
            }
            total += n;
        }
        return true;
    }
}
