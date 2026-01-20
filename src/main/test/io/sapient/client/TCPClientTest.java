package io.sapient.client;

import org.junit.jupiter.api.*;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TCPClientTest {

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    @Test
    void testTwoWayCommunication() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();

            AtomicReference<String> clientHello = new AtomicReference<>();
            AtomicReference<String> serverResponse = new AtomicReference<>();

            CountDownLatch clientReceivedLatch = new CountDownLatch(1);
            CountDownLatch serverReceivedLatch = new CountDownLatch(1);

            // server task
            executor.submit(() -> {
                try (Socket sock = server.accept()) {
                    // send to client
                    sock.getOutputStream().write("Hello Client".getBytes());

                    // read from client
                    byte[] buf = new byte[1024];
                    int len = sock.getInputStream().read(buf);
                    clientHello.set(new String(buf, 0, len));

                    serverReceivedLatch.countDown(); // signal server read done
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

            TCPClient client = new TCPClient("localhost", port);

            client.subscribe(bb -> {
                byte[] data = new byte[bb.remaining()];
                bb.get(data);
                serverResponse.set(new String(data));
                clientReceivedLatch.countDown(); // signal client received
            });

            // start client loop
            executor.submit(() -> {
                try {
                    client.run();
                } catch (IOException ex) {
                    throw new RuntimeException(ex);
                }
            });

            Thread.sleep(10);

            // send message from client → server
            client.write(ByteBuffer.wrap("Hello Server".getBytes()));

            // wait for both directions
            serverReceivedLatch.await(); // server got client message
            clientReceivedLatch.await(); // client got server message

            // assert messages
            assertEquals("Hello Server", clientHello.get());
            assertEquals("Hello Client", serverResponse.get());

            client.close();
        }
    }
}
