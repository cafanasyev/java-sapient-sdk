package io.sapient.transmission;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

import io.sapient.transport.IClient;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.ArgumentCaptor;
import uk.gov.dstl.sapientmsg.bsiflex335v2.Registration;
import uk.gov.dstl.sapientmsg.bsiflex335v2.RegistrationAck;
import uk.gov.dstl.sapientmsg.bsiflex335v2.SapientMessage;
import uk.gov.dstl.sapientmsg.bsiflex335v2.StatusReport;

@Execution(ExecutionMode.CONCURRENT)
class NodeDispatcherTest {

    // --- Utilities ---

    @SuppressWarnings("unchecked")
    static Consumer<ByteBuffer> captureSubscription(IClient client) {
        ArgumentCaptor<Consumer<ByteBuffer>> captor = ArgumentCaptor.forClass(Consumer.class);
        verify(client).subscribe(captor.capture());
        return captor.getValue();
    }

    static INode mockNode(UUID id, AtomicBoolean online, long statusReportInterval) {
        INode node = mock(INode.class);
        when(node.getNodeId()).thenReturn(id);
        when(node.isOnline()).thenAnswer(inv -> online.get());
        when(node.getRegistration()).thenReturn(buildRegistration(statusReportInterval));
        when(node.getStatusReport()).thenReturn(StatusReport.getDefaultInstance());
        return node;
    }

    static Registration buildRegistration(long statusReportInterval) {
        return Registration.newBuilder()
                .setStatusDefinition(
                        Registration.StatusDefinition.newBuilder()
                                .setStatusInterval(
                                        Registration.Duration.newBuilder()
                                                .setValue(statusReportInterval)
                                                .setUnits(
                                                        Registration.TimeUnits
                                                                .TIME_UNITS_MILLISECONDS)))
                .build();
    }

    static void sendAck(Consumer<ByteBuffer> onMessage, UUID nodeId, boolean accepted) {
        SapientMessage msg =
                SapientMessage.newBuilder()
                        .setDestinationId(nodeId.toString())
                        .setRegistrationAck(RegistrationAck.newBuilder().setAcceptance(accepted))
                        .build();
        onMessage.accept(ByteBuffer.wrap(msg.toByteArray()));
    }

    record Setup(
            NodeDispatcher dispatcher,
            INode node,
            AtomicBoolean online,
            Consumer<ByteBuffer> onMessage,
            UUID nodeId)
            implements AutoCloseable {
        @Override
        public void close() {
            dispatcher.close();
        }
    }

    static Setup setup(long statusReportInterval) {
        UUID nodeId = UUID.randomUUID();
        AtomicBoolean online = new AtomicBoolean(false);
        IClient client = mock(IClient.class);
        INode node = mockNode(nodeId, online, statusReportInterval);
        NodeDispatcher dispatcher =
                spy(
                        new NodeDispatcher(
                                client,
                                new NodeDispatcherConfig(
                                        Duration.ofMillis(10), Duration.ofSeconds(5))));
        Consumer<ByteBuffer> onMessage = captureSubscription(client);
        return new Setup(dispatcher, node, online, onMessage, nodeId);
    }

    // --- Tests ---

    @Test
    @Timeout(3)
    void registrationSentWhenNodeComesOnline() throws Exception {
        try (var s = setup(200)) {
            s.online.set(true);
            s.dispatcher.register(s.node);

            verify(s.dispatcher, timeout(1000))
                    .publish(any(Registration.class), eq(s.nodeId), any(Duration.class));
        }
    }

    @Test
    @Timeout(3)
    void registrationAckDeliveredToNode() throws Exception {
        try (var s = setup(200)) {
            s.online.set(true);
            s.dispatcher.register(s.node);

            verify(s.dispatcher, timeout(1000))
                    .publish(any(Registration.class), eq(s.nodeId), any(Duration.class));

            sendAck(s.onMessage, s.nodeId, true);
            verify(s.node, timeout(1000)).onRegistrationAck(argThat(RegistrationAck::getAcceptance));
        }
    }

    @Test
    @Timeout(3)
    void statusReportsSentAfterAcceptedAck() throws Exception {
        try (var s = setup(50)) {
            s.online.set(true);
            s.dispatcher.register(s.node);

            verify(s.dispatcher, timeout(1000))
                    .publish(any(Registration.class), eq(s.nodeId), any(Duration.class));

            sendAck(s.onMessage, s.nodeId, true);

            verify(s.dispatcher, timeout(1000).atLeast(2))
                    .publish(any(StatusReport.class), eq(s.nodeId), any(Duration.class));
        }
    }

    @Test
    @Timeout(3)
    void noStatusReportsBeforeAck() throws Exception {
        try (var s = setup(50)) {
            s.online.set(true);
            s.dispatcher.register(s.node);

            verify(s.dispatcher, timeout(1000))
                    .publish(any(Registration.class), eq(s.nodeId), any(Duration.class));

            Thread.sleep(200);

            verify(s.dispatcher, never()).publish(any(StatusReport.class), eq(s.nodeId), any(Duration.class));
        }
    }

    @Test
    @Timeout(3)
    void rejectedAckRetriesRegistration() throws Exception {
        try (var s = setup(200)) {
            s.online.set(true);
            s.dispatcher.register(s.node);

            verify(s.dispatcher, timeout(1000))
                    .publish(any(Registration.class), eq(s.nodeId), any(Duration.class));
            sendAck(s.onMessage, s.nodeId, false);

            verify(s.dispatcher, timeout(1000).atLeast(2))
                    .publish(any(Registration.class), eq(s.nodeId), any(Duration.class));
        }
    }

    @Test
    @Timeout(3)
    void goodbyeSentWhenNodeGoesOffline() throws Exception {
        try (var s = setup(50)) {
            s.online.set(true);
            s.dispatcher.register(s.node);

            verify(s.dispatcher, timeout(1000))
                    .publish(any(Registration.class), eq(s.nodeId), any(Duration.class));

            sendAck(s.onMessage, s.nodeId, true);

            verify(s.dispatcher, timeout(1000).atLeastOnce())
                    .publish(any(StatusReport.class), eq(s.nodeId), any(Duration.class));

            s.online.set(false);

            verify(s.dispatcher, timeout(1000)).goodbye(eq(s.nodeId), any(Duration.class));
        }
    }

    @Test
    @Timeout(3)
    void reRegistrationAfterOfflineOnline() throws Exception {
        try (var s = setup(50)) {
            s.online.set(true);
            s.dispatcher.register(s.node);

            // first registration
            verify(s.dispatcher, timeout(1000))
                    .publish(any(Registration.class), eq(s.nodeId), any(Duration.class));

            sendAck(s.onMessage, s.nodeId, true);

            verify(s.dispatcher, timeout(1000).atLeastOnce())
                    .publish(any(StatusReport.class), eq(s.nodeId), any(Duration.class));

            // go offline, then online
            s.online.set(false);

            verify(s.dispatcher, timeout(1000)).goodbye(eq(s.nodeId), any(Duration.class));

            s.online.set(true);

            // second registration
            verify(s.dispatcher, timeout(1000).atLeast(2))
                    .publish(any(Registration.class), eq(s.nodeId), any(Duration.class));

            sendAck(s.onMessage, s.nodeId, true);

            verify(s.dispatcher, timeout(1000).atLeast(2))
                    .publish(any(StatusReport.class), eq(s.nodeId), any(Duration.class));
        }
    }

    @Test
    @Timeout(3)
    void unregisterSendsGoodbyeWhenRegistered() throws Exception {
        try (var s = setup(200)) {
            s.online.set(true);
            s.dispatcher.register(s.node);

            verify(s.dispatcher, timeout(1000))
                    .publish(any(Registration.class), eq(s.nodeId), any(Duration.class));

            sendAck(s.onMessage, s.nodeId, true);

            Thread.sleep(50); // let registered flag be set

            s.dispatcher.unregister(s.node);

            verify(s.dispatcher, timeout(1000)).goodbye(eq(s.nodeId), any(Duration.class));
        }
    }

    @Test
    @Timeout(3)
    void unregisterNoGoodbyeWhenNotRegistered() throws Exception {
        try (var s = setup(200)) {
            s.online.set(true);
            s.dispatcher.register(s.node);

            verify(s.dispatcher, timeout(1000))
                    .publish(any(Registration.class), eq(s.nodeId), any(Duration.class));

            // no ack sent — node not registered yet
            s.dispatcher.unregister(s.node);

            Thread.sleep(100);

            verify(s.dispatcher, never()).goodbye(any(), any(Duration.class));
        }
    }

    @Test
    @Timeout(3)
    void closeUnblocksRun() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);

        IClient client = mock(IClient.class);
        doAnswer(
                        inv -> {
                            running.countDown();
                            stopped.await();
                            return null;
                        })
                .when(client)
                .run();

        doAnswer(
                        inv -> {
                            stopped.countDown();
                            return null;
                        })
                .when(client)
                .close();

        NodeDispatcher dispatcher =
                new NodeDispatcher(
                        client,
                        new NodeDispatcherConfig(Duration.ofMillis(10), Duration.ofSeconds(5)));

        Thread thread = Thread.ofVirtual().start(dispatcher);

        running.await();
        dispatcher.close();

        thread.join(2000);

        assertFalse(thread.isAlive(), "dispatcher.run() should have returned after close()");
    }
}
