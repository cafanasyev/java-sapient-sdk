package io.sapient;

import io.sapient.transmission.NodeDispatcher;
import io.sapient.transmission.NodeDispatcherConfig;
import io.sapient.transport.SocketClient;
import io.sapient.transport.ISocketProvider;
import io.sapient.transport.TestServer;
import java.io.IOException;
import java.net.Socket;
import java.time.Duration;
import java.util.UUID;
import lombok.SneakyThrows;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SapientSdkIT {

    private TestServer server;
    private NodeDispatcher dispatcher;

    @BeforeAll
    void startApp() throws Exception {
        server = new TestServer();
        Thread.ofVirtual().start(server);

        SocketClient client =
                new SocketClient(
                        new ISocketProvider() {
                            @Override
                            public Socket get() throws IOException {
                                return socket(server.getLocalPort());
                            }

                            @Override
                            public String host() {
                                return "localhost";
                            }

                            @Override
                            public int port() {
                                return server.getLocalPort();
                            }
                        });
        dispatcher =
                new NodeDispatcher(
                        client,
                        NodeDispatcherConfig.defaults(UUID.randomUUID(), Duration.ofSeconds(12)));
    }

    @AfterAll
    void stopApp() throws Exception {
        dispatcher.close();
    }

    @SneakyThrows
    private static Socket socket(int port) {
        return new Socket("localhost", port);
    }

    @Test
    void smokeTest() {
        System.out.println("smokeTest");
        // verifies app starts and stops without errors
    }
}