package io.sapient.transport;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import lombok.Getter;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Assertions;

public class TestServer implements Runnable, AutoCloseable {
    private static final int AUTO_ALLOCATED_PORT = 0;
    private final ServerSocket serverSocket;
    private final AtomicBoolean running = new AtomicBoolean(true);
    @Getter private final BlockingQueue<String> clientMessages = new ArrayBlockingQueue<>(8);

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

        writeFramed(out, "Hello from server!".getBytes());

        while (running.get()) {
            byte[] msg = readFramed(in);
            if (msg == null) {
                running.set(false);
                break;
            }

            System.out.println(new String(msg));

            boolean offer = clientMessages.offer(new String(msg), 3, TimeUnit.SECONDS);
            Assertions.assertTrue(offer, "Failed to insert client message to the queue");
        }

        clientConnection.close();
    }

    private static void writeFramed(OutputStream out, byte[] data) throws IOException {
        byte[] frame = ByteBuffer.allocate(4 + data.length).putInt(data.length).put(data).array();
        out.write(frame);
        out.flush();
    }

    private static byte[] readFramed(InputStream in) throws IOException {
        byte[] lenBuf = new byte[4];
        if (!readFully(in, lenBuf, 4)) {
            return null;
        }
        int len = ByteBuffer.wrap(lenBuf).getInt();
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
