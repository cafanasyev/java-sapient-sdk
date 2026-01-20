package io.sapient;

import io.sapient.transmission.NodeDispatcher;
import io.sapient.transmission.NodeDispatcherConfig;
import io.sapient.transport.SocketClient;
import io.sapient.transport.TestServer;
import lombok.SneakyThrows;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.net.Socket;
import java.time.Duration;


@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SapientSdkIT {

    private TestServer server;
    private NodeDispatcher dispatcher;
    private Thread dispatcherThread;

    @BeforeAll
    void startApp() throws Exception {
        server = new TestServer();
        Thread.ofVirtual().start(server);

        SocketClient client = new SocketClient(() -> socket(server.getLocalPort()));
        dispatcher = new NodeDispatcher(client, new NodeDispatcherConfig(Duration.ofMillis(100), Duration.ofSeconds(5)));
        dispatcherThread = Thread.ofVirtual().start(dispatcher);
    }

    @AfterAll
    void stopApp() throws Exception {
        dispatcher.close();
        dispatcherThread.join(5000);
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