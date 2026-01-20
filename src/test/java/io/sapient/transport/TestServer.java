package io.sapient.transport;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
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
        clientConnection.getOutputStream().write("Hello from server!".getBytes());

        while (running.get()) {
            // read from client
            byte[] buf = new byte[1024];
            int len = clientConnection.getInputStream().read(buf);

            if (len > 0) {
                System.out.println(new String(buf, 0, len));
            }

            if (len < 0) {
                running.set(false);
                break;
            }

            boolean offer = clientMessages.offer(new String(buf, 0, len), 3, TimeUnit.SECONDS);

            Assertions.assertTrue(offer, "Failed to insert client message to the queue");
        }

        clientConnection.close();
    }
}
