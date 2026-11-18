package io.sapient.transmission;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import io.sapient.testing.FakeEdgeNode;
import io.sapient.testing.FakeFusionNode;
import io.sapient.testing.TestConfig;
import io.sapient.transport.ConnectionState;
import io.sapient.transport.ISocketProvider;
import io.sapient.transport.SocketClient;
import io.sapient.transport.health.HealthCheckConfig;
import io.sapient.transport.health.HealthCheckType;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import uk.gov.dstl.sapientmsg.bsiflex335v2.RegistrationAck;
import uk.gov.dstl.sapientmsg.bsiflex335v2.SapientMessage;

/**
 * Re-registration over a real {@link SocketClient} and real TCP. Only a real client has a
 * connection loss detection delay, and here it is that delay which pushes a short outage over the
 * grace period. The test runs in real time on purpose, so its waits are real-time bounds.
 */
@Execution(ExecutionMode.CONCURRENT)
class ReRegistrationOverTcpTest {

    /** Upper bound for a real-time wait; generous, because a loaded machine is slow, not wrong. */
    private static final long AWAIT_MS = 5000;

    @Test
    void reRegistrationWhenDetectionDelayPushesGapOverGracePeriod() throws Exception {
        // Closing server1 with its accepted socket still alive leaves readLoop blocked on
        // a half-open TCP connection. The SocketClient watchdog (100ms interval) is the only
        // thing that can detect the server is gone: next probe fails → watchdog closes the
        // socket → readLoop wakes → DISCONNECTED. On reconnect, connectionLossDetectionDelay
        // (150ms) added to the raw gap crosses the 100ms grace → epoch bumps → re-register.
        // The 500ms status interval keeps the server retention window
        // (0.95 × (3 × 500ms + 100ms − 150ms) ≈ 1.4s) well beyond the time the watchdog needs
        // to notice the outage, so the reconnect is what re-registers the node here, not an
        // expired retention window.
        var node = FakeEdgeNode.builder("n1").withStatusInterval(Duration.ofMillis(500)).build();

        ServerSocket server1 = new ServerSocket(0);
        AtomicInteger port = new AtomicInteger(server1.getLocalPort());
        SocketClient client =
                spy(
                        new SocketClient(
                                socketProvider(port),
                                new HealthCheckConfig(
                                        HealthCheckType.NETCAT,
                                        Duration.ofMillis(100),
                                        Duration.ofMillis(100),
                                        1),
                                Duration.ofMillis(10)));
        LinkedBlockingQueue<ConnectionState> states = new LinkedBlockingQueue<>();
        client.addStateChangeListener((s, ts) -> states.add(s));
        BlockingQueue<SapientMessage> registrations = new LinkedBlockingQueue<>();
        Thread.startVirtualThread(() -> runTestServer(server1, node.id(), registrations));

        var config = TestConfig.builder().withReconnectGracePeriod(Duration.ofMillis(100)).build();
        try (NodeDispatcher dispatcher = new NodeDispatcher(client, config);
                ServerSocket server2 = new ServerSocket(0)) {
            dispatcher.register(node.asNode());
            assertNotNull(registrations.poll(3, TimeUnit.SECONDS), "initial registration");
            long epochBefore = dispatcher.reregistrationEpoch();

            server1.close();
            assertTrue(
                    drainUntil(states, ConnectionState.DISCONNECTED),
                    "expected DISCONNECTED state");
            // the watchdog is the sole driver of disconnect detection on a half-open socket —
            // asserting it ran at least once confirms the expected path was exercised
            verify(client, timeout(3000).atLeastOnce()).probeReachable(any(Duration.class));

            Thread.startVirtualThread(() -> runTestServer(server2, node.id(), registrations));
            port.set(server2.getLocalPort());

            assertNotNull(
                    registrations.poll(5, TimeUnit.SECONDS), "re-registration after reconnect");
            // the re-registration can reach the server just before the CONNECTED event is
            // processed, so wait for the bump instead of sampling the epoch once
            awaitEpochIncrement(dispatcher, epochBefore);
        }
    }

    /** Waits for the dispatcher to bump its re-registration epoch above {@code before}. */
    private static void awaitEpochIncrement(NodeDispatcher dispatcher, long before)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofMillis(AWAIT_MS).toNanos();
        while (System.nanoTime() < deadline) {
            if (dispatcher.reregistrationEpoch() > before) return;
            Thread.sleep(20);
        }
        fail("epoch should increment within " + AWAIT_MS + "ms");
    }

    private static ISocketProvider socketProvider(AtomicInteger port) {
        return new ISocketProvider() {
            @Override
            public Socket get() throws IOException {
                return new Socket("localhost", port.get());
            }

            @Override
            public String host() {
                return "localhost";
            }

            @Override
            public int port() {
                return port.get();
            }
        };
    }

    private static boolean drainUntil(BlockingQueue<ConnectionState> states, ConnectionState target)
            throws InterruptedException {
        ConnectionState s;
        while ((s = states.poll(3, TimeUnit.SECONDS)) != null) {
            if (s == target) return true;
        }
        return false;
    }

    private static void runTestServer(
            ServerSocket serverSocket, UUID nodeId, BlockingQueue<SapientMessage> registrations) {
        // loop-accept: SocketClient's probe-before-connect opens and immediately closes
        // a TCP connection, which would otherwise consume the single accept intended
        // for the real client
        while (!serverSocket.isClosed()) {
            try (Socket s = serverSocket.accept()) {
                InputStream in = s.getInputStream();
                OutputStream out = s.getOutputStream();
                byte[] lenBuf = new byte[4];
                while (readFully(in, lenBuf, 4)) {
                    int len = ByteBuffer.wrap(lenBuf).order(ByteOrder.LITTLE_ENDIAN).getInt();
                    byte[] buf = new byte[len];
                    if (!readFully(in, buf, len)) break;
                    SapientMessage msg = SapientMessage.parseFrom(buf);
                    if (msg.getContentCase() != SapientMessage.ContentCase.REGISTRATION) continue;
                    registrations.offer(msg);
                    SapientMessage ack =
                            SapientMessage.newBuilder()
                                    .setNodeId(FakeFusionNode.ID.toString())
                                    .setDestinationId(nodeId.toString())
                                    .setRegistrationAck(
                                            RegistrationAck.newBuilder().setAcceptance(true))
                                    .build();
                    byte[] ackBytes = ack.toByteArray();
                    out.write(
                            ByteBuffer.allocate(4 + ackBytes.length)
                                    .order(ByteOrder.LITTLE_ENDIAN)
                                    .putInt(ackBytes.length)
                                    .put(ackBytes)
                                    .array());
                    out.flush();
                }
            } catch (IOException e) {
                // socket closed by the test or probe — loop back to accept the next one
            }
        }
    }

    private static boolean readFully(InputStream in, byte[] buf, int count) throws IOException {
        int total = 0;
        while (total < count) {
            int n = in.read(buf, total, count - total);
            if (n < 0) return false;
            total += n;
        }
        return true;
    }
}
