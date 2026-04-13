package io.sapient.transport;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import uk.gov.dstl.sapientmsg.bsiflex335v2.Registration;
import uk.gov.dstl.sapientmsg.bsiflex335v2.SapientMessage;

@Execution(ExecutionMode.CONCURRENT)
class SocketClientTest {

    private static final SapientMessage CLIENT_MESSAGE =
            SapientMessage.newBuilder().setRegistration(Registration.getDefaultInstance()).build();

    @FunctionalInterface
    private interface SocketSupplier {
        Socket get() throws IOException;
    }

    private static class TestSocketProvider implements SocketProvider {
        private final String host;
        private final IntSupplier portSupplier;
        private final SocketSupplier socketSupplier;

        TestSocketProvider(String host, IntSupplier portSupplier, SocketSupplier socketSupplier) {
            this.host = host;
            this.portSupplier = portSupplier;
            this.socketSupplier = socketSupplier;
        }

        @Override
        public Socket get() throws IOException {
            return socketSupplier.get();
        }

        @Override
        public String host() {
            return host;
        }

        @Override
        public int port() {
            return portSupplier.getAsInt();
        }
    }

    private static SocketClient newClient(
            SocketProvider provider, ArrayBlockingQueue<SapientMessage> received) {
        SocketClient client = new SocketClient(provider);
        client.subscribe(received::offer);
        return client;
    }

    @Test
    @Timeout(3)
    void testTwoWayCommunication() throws Exception {
        var server = new TestServer();
        int port = server.getLocalPort();
        var received = new ArrayBlockingQueue<SapientMessage>(1);
        var provider =
                new TestSocketProvider(
                        "localhost", () -> port, () -> new Socket("localhost", port));
        var client = newClient(provider, received);

        try (server;
                client) {
            Thread.startVirtualThread(server);
            client.start();

            assertEquals(TestServer.GREETING, received.poll(3, SECONDS));
            client.publish(CLIENT_MESSAGE, Duration.ofSeconds(3));
            assertEquals(CLIENT_MESSAGE, server.getClientMessages().poll(3, SECONDS));
        }
    }

    @Test
    @Timeout(10)
    void testClientReconnect() throws Exception {
        var tls = new TestDerTlsConfig();
        var serverCtx = tls.serverContext();
        var clientCtx = tls.clientContext();
        var server = new TestServer(serverCtx);
        var port = new AtomicInteger(server.getLocalPort());
        var received = new ArrayBlockingQueue<SapientMessage>(1);
        var provider =
                new TestSocketProvider(
                        "localhost",
                        port::get,
                        () -> clientCtx.getSocketFactory().createSocket("localhost", port.get()));
        var client = newClient(provider, received);

        try (client) {
            Thread.startVirtualThread(server);
            client.start();

            assertEquals(TestServer.GREETING, received.poll(3, SECONDS));
            client.publish(CLIENT_MESSAGE, Duration.ofSeconds(3));
            assertEquals(CLIENT_MESSAGE, server.getClientMessages().poll(3, SECONDS));

            server.close();

            var newServer = new TestServer(serverCtx);
            port.set(newServer.getLocalPort());
            Thread.startVirtualThread(newServer);

            assertEquals(TestServer.GREETING, received.poll(5, SECONDS));
            client.publish(CLIENT_MESSAGE, Duration.ofSeconds(5));
            assertEquals(CLIENT_MESSAGE, newServer.getClientMessages().poll(3, SECONDS));

            newServer.close();
        }
    }
}
