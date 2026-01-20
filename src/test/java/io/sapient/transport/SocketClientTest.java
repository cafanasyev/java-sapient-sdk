package io.sapient.transport;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.Socket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

@Execution(ExecutionMode.CONCURRENT)
class SocketClientTest {

    private static SocketClient newClient(
            SocketProvider provider, ArrayBlockingQueue<String> received) {
        SocketClient client = new SocketClient(provider);
        client.subscribe(
                bb -> {
                    byte[] data = new byte[bb.remaining()];
                    bb.get(data);
                    try {
                        received.offer(new String(data), 3, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                });
        return client;
    }

    @Test
    @Timeout(3)
    void testTwoWayCommunication() throws Exception {
        var server = new TestServer();
        int port = server.getLocalPort();
        var received = new ArrayBlockingQueue<String>(1);
        var client = newClient(() -> new Socket("localhost", port), received);

        try (server;
                client) {
            Thread.startVirtualThread(server);
            Thread.startVirtualThread(client);

            assertEquals("Hello from server!", received.poll(3, SECONDS));
            client.publish(ByteBuffer.wrap("Hello from client!".getBytes()), Duration.ofSeconds(3));
            assertEquals("Hello from client!", server.getClientMessages().poll(3, SECONDS));
        }
    }

    @Test
    @Timeout(10)
    void testClientReconnect() throws Exception {
        var tls = new TestTlsConfig();
        var serverCtx = tls.serverContext();
        var clientCtx = tls.clientContext();
        var server = new TestServer(serverCtx);
        var port = new AtomicInteger(server.getLocalPort());
        var received = new ArrayBlockingQueue<String>(1);
        var client =
                newClient(
                        () -> clientCtx.getSocketFactory().createSocket("localhost", port.get()),
                        received);

        try (client) {
            Thread.startVirtualThread(server);
            Thread.startVirtualThread(client);

            assertEquals("Hello from server!", received.poll(3, SECONDS));
            client.publish(
                    ByteBuffer.wrap("Hello from client(1)!".getBytes()), Duration.ofSeconds(3));
            assertEquals("Hello from client(1)!", server.getClientMessages().poll(3, SECONDS));

            server.close();

            var newServer = new TestServer(serverCtx);
            port.set(newServer.getLocalPort());
            Thread.startVirtualThread(newServer);

            assertEquals("Hello from server!", received.poll(5, SECONDS));
            client.publish(
                    ByteBuffer.wrap("Hello from client(2)!".getBytes()), Duration.ofSeconds(5));
            assertEquals("Hello from client(2)!", newServer.getClientMessages().poll(3, SECONDS));

            newServer.close();
        }
    }
}
