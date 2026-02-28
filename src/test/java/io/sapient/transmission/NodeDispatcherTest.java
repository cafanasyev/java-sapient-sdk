package io.sapient.transmission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

import io.sapient.transport.IClient;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.ArgumentCaptor;
import uk.gov.dstl.sapientmsg.bsiflex335v2.Alert;
import uk.gov.dstl.sapientmsg.bsiflex335v2.AlertAck;
import uk.gov.dstl.sapientmsg.bsiflex335v2.DetectionReport;
import uk.gov.dstl.sapientmsg.bsiflex335v2.Registration;
import uk.gov.dstl.sapientmsg.bsiflex335v2.RegistrationAck;
import uk.gov.dstl.sapientmsg.bsiflex335v2.SapientMessage;
import uk.gov.dstl.sapientmsg.bsiflex335v2.StatusReport;
import uk.gov.dstl.sapientmsg.bsiflex335v2.Task;

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

    static final UUID FUSION_NODE_ID = UUID.randomUUID();

    static void sendAck(Consumer<ByteBuffer> onMessage, UUID nodeId, boolean accepted) {
        SapientMessage msg =
                SapientMessage.newBuilder()
                        .setNodeId(FUSION_NODE_ID.toString())
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
            verify(s.node, timeout(1000))
                    .onRegistrationAck(argThat(RegistrationAck::getAcceptance));
        }
    }

    @Test
    @Timeout(3)
    void fusionNodeIdNullBeforeAck() throws Exception {
        try (var s = setup(200)) {
            s.online.set(true);
            s.dispatcher.register(s.node);

            verify(s.dispatcher, timeout(1000))
                    .publish(any(Registration.class), eq(s.nodeId), any(Duration.class));

            assertNull(s.dispatcher.nodes.get(s.nodeId).fusionNodeId.get());
        }
    }

    @Test
    @Timeout(3)
    void fusionNodeIdNotSetAfterAcceptAck() throws Exception {
        try (var s = setup(200)) {
            s.online.set(true);
            s.dispatcher.register(s.node);

            verify(s.dispatcher, timeout(1000))
                    .publish(any(Registration.class), eq(s.nodeId), any(Duration.class));

            sendAck(s.onMessage, s.nodeId, true);
            verify(s.node, timeout(1000)).onRegistrationAck(any());

            assertEquals(FUSION_NODE_ID, s.dispatcher.nodes.get(s.nodeId).fusionNodeId.get());
        }
    }

    @Test
    @Timeout(3)
    void fusionNodeIdNotSetAfterRejectedAck() throws Exception {
        try (var s = setup(200)) {
            s.online.set(true);
            s.dispatcher.register(s.node);

            verify(s.dispatcher, timeout(1000))
                    .publish(any(Registration.class), eq(s.nodeId), any(Duration.class));

            sendAck(s.onMessage, s.nodeId, false);
            verify(s.node, timeout(1000)).onRegistrationAck(any());

            assertNull(s.dispatcher.nodes.get(s.nodeId).fusionNodeId.get());
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

            verify(s.dispatcher, never())
                    .publish(any(StatusReport.class), eq(s.nodeId), any(Duration.class));
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
    void alertAckDeliveredToNode() throws Exception {
        try (var s = setup(200)) {
            s.online.set(true);
            s.dispatcher.register(s.node);

            AlertAck alertAck = AlertAck.newBuilder().setAlertId("alert-1").build();
            SapientMessage msg =
                    SapientMessage.newBuilder()
                            .setDestinationId(s.nodeId.toString())
                            .setAlertAck(alertAck)
                            .build();
            s.onMessage.accept(ByteBuffer.wrap(msg.toByteArray()));

            verify(s.node, timeout(1000))
                    .onAlertAck(argThat(ack -> "alert-1".equals(ack.getAlertId())));
        }
    }

    @Test
    @Timeout(3)
    void taskDeliveredToNode() throws Exception {
        try (var s = setup(200)) {
            s.online.set(true);
            s.dispatcher.register(s.node);

            Task task = Task.newBuilder().setTaskId("task-1").build();
            SapientMessage msg =
                    SapientMessage.newBuilder()
                            .setDestinationId(s.nodeId.toString())
                            .setTask(task)
                            .build();
            s.onMessage.accept(ByteBuffer.wrap(msg.toByteArray()));

            verify(s.node, timeout(1000)).onTask(argThat(t -> "task-1".equals(t.getTaskId())));
        }
    }

    static SapientMessage capturePublished(IClient client) throws Exception {
        ArgumentCaptor<ByteBuffer> captor = ArgumentCaptor.forClass(ByteBuffer.class);
        verify(client).publish(captor.capture(), any(Duration.class));
        return SapientMessage.parseFrom(captor.getValue().array());
    }

    static Instant toInstant(com.google.protobuf.Timestamp ts) {
        return Instant.ofEpochSecond(ts.getSeconds(), ts.getNanos());
    }

    static void assertRecentTimestamp(SapientMessage msg, Instant before) {
        assertTrue(msg.hasTimestamp(), "message should have a timestamp");
        Instant ts = toInstant(msg.getTimestamp());
        Instant after = Instant.now();
        assertFalse(ts.isBefore(before), "timestamp should not be before the call");
        assertFalse(ts.isAfter(after), "timestamp should not be after now");
    }

    @Test
    @Timeout(3)
    void publishRegistrationSerializesToClient() throws Exception {
        IClient client = mock(IClient.class);
        NodeDispatcher dispatcher = new NodeDispatcher(client, NodeDispatcherConfig.defaults());
        UUID nodeId = UUID.randomUUID();

        Instant before = Instant.now();
        dispatcher.publish(Registration.getDefaultInstance(), nodeId, Duration.ofSeconds(1));

        SapientMessage msg = capturePublished(client);
        assertEquals(SapientMessage.ContentCase.REGISTRATION, msg.getContentCase());
        assertEquals(nodeId.toString(), msg.getNodeId());
        assertRecentTimestamp(msg, before);
        dispatcher.close();
    }

    @Test
    @Timeout(3)
    void publishStatusReportSerializesToClient() throws Exception {
        IClient client = mock(IClient.class);
        NodeDispatcher dispatcher = new NodeDispatcher(client, NodeDispatcherConfig.defaults());
        UUID nodeId = UUID.randomUUID();

        Instant before = Instant.now();
        dispatcher.publish(StatusReport.getDefaultInstance(), nodeId, Duration.ofSeconds(1));

        SapientMessage msg = capturePublished(client);
        assertEquals(SapientMessage.ContentCase.STATUS_REPORT, msg.getContentCase());
        assertEquals(nodeId.toString(), msg.getNodeId());
        assertRecentTimestamp(msg, before);
        dispatcher.close();
    }

    @Test
    @Timeout(3)
    void publishAlertSerializesToClient() throws Exception {
        IClient client = mock(IClient.class);
        NodeDispatcher dispatcher = new NodeDispatcher(client, NodeDispatcherConfig.defaults());
        UUID nodeId = UUID.randomUUID();

        Instant before = Instant.now();
        dispatcher.publish(Alert.getDefaultInstance(), nodeId, Duration.ofSeconds(1));

        SapientMessage msg = capturePublished(client);
        assertEquals(SapientMessage.ContentCase.ALERT, msg.getContentCase());
        assertEquals(nodeId.toString(), msg.getNodeId());
        assertRecentTimestamp(msg, before);
        dispatcher.close();
    }

    @Test
    @Timeout(3)
    void publishDetectionReportSerializesToClient() throws Exception {
        IClient client = mock(IClient.class);
        NodeDispatcher dispatcher = new NodeDispatcher(client, NodeDispatcherConfig.defaults());
        UUID nodeId = UUID.randomUUID();

        Instant before = Instant.now();
        dispatcher.publish(DetectionReport.getDefaultInstance(), nodeId, Duration.ofSeconds(1));

        SapientMessage msg = capturePublished(client);
        assertEquals(SapientMessage.ContentCase.DETECTION_REPORT, msg.getContentCase());
        assertEquals(nodeId.toString(), msg.getNodeId());
        assertRecentTimestamp(msg, before);
        dispatcher.close();
    }

    @Test
    @Timeout(3)
    void goodbyeSerializesToClient() throws Exception {
        IClient client = mock(IClient.class);
        NodeDispatcher dispatcher = new NodeDispatcher(client, NodeDispatcherConfig.defaults());
        UUID nodeId = UUID.randomUUID();

        Instant before = Instant.now();
        dispatcher.goodbye(nodeId, Duration.ofSeconds(1));

        SapientMessage msg = capturePublished(client);
        assertEquals(SapientMessage.ContentCase.CONTENT_NOT_SET, msg.getContentCase());
        assertEquals(nodeId.toString(), msg.getNodeId());
        assertRecentTimestamp(msg, before);
        dispatcher.close();
    }

    @Test
    @Timeout(3)
    void publishSetsDestinationIdWhenFusionNodeIdKnown() throws Exception {
        IClient client = mock(IClient.class);
        NodeDispatcher dispatcher = new NodeDispatcher(client, NodeDispatcherConfig.defaults());
        UUID nodeId = UUID.randomUUID();
        UUID fusionId = UUID.randomUUID();

        dispatcher.register(mockNode(nodeId, new AtomicBoolean(false), 200));
        dispatcher.nodes.get(nodeId).fusionNodeId.set(fusionId);

        dispatcher.publish(StatusReport.getDefaultInstance(), nodeId, Duration.ofSeconds(1));

        SapientMessage msg = capturePublished(client);
        assertEquals(fusionId.toString(), msg.getDestinationId());
        dispatcher.close();
    }

    @Test
    @Timeout(3)
    void publishOmitsDestinationIdWhenFusionNodeIdUnknown() throws Exception {
        IClient client = mock(IClient.class);
        NodeDispatcher dispatcher = new NodeDispatcher(client, NodeDispatcherConfig.defaults());
        UUID nodeId = UUID.randomUUID();

        dispatcher.register(mockNode(nodeId, new AtomicBoolean(false), 200));

        dispatcher.publish(StatusReport.getDefaultInstance(), nodeId, Duration.ofSeconds(1));

        SapientMessage msg = capturePublished(client);
        assertEquals("", msg.getDestinationId());
        dispatcher.close();
    }

    @Test
    @Timeout(3)
    void statusReportInfoNewOnFirstPublish() throws Exception {
        IClient client = mock(IClient.class);
        NodeDispatcher dispatcher = new NodeDispatcher(client, NodeDispatcherConfig.defaults());
        UUID nodeId = UUID.randomUUID();
        dispatcher.register(mockNode(nodeId, new AtomicBoolean(false), 200));

        dispatcher.publish(StatusReport.getDefaultInstance(), nodeId, Duration.ofSeconds(1));

        SapientMessage msg = capturePublished(client);
        assertEquals(StatusReport.Info.INFO_NEW, msg.getStatusReport().getInfo());
        dispatcher.close();
    }

    @Test
    @Timeout(3)
    void statusReportInfoUnchangedOnIdenticalPublish() throws Exception {
        IClient client = mock(IClient.class);
        NodeDispatcher dispatcher = new NodeDispatcher(client, NodeDispatcherConfig.defaults());
        UUID nodeId = UUID.randomUUID();
        dispatcher.register(mockNode(nodeId, new AtomicBoolean(false), 200));

        StatusReport report = StatusReport.newBuilder().setMode("patrol").build();
        dispatcher.publish(report, nodeId, Duration.ofSeconds(1));
        dispatcher.publish(report, nodeId, Duration.ofSeconds(1));

        ArgumentCaptor<ByteBuffer> captor = ArgumentCaptor.forClass(ByteBuffer.class);
        verify(client, times(2)).publish(captor.capture(), any(Duration.class));
        SapientMessage second = SapientMessage.parseFrom(captor.getAllValues().get(1).array());
        assertEquals(StatusReport.Info.INFO_UNCHANGED, second.getStatusReport().getInfo());
        dispatcher.close();
    }

    @Test
    @Timeout(3)
    void statusReportInfoNewOnChangedPublish() throws Exception {
        IClient client = mock(IClient.class);
        NodeDispatcher dispatcher = new NodeDispatcher(client, NodeDispatcherConfig.defaults());
        UUID nodeId = UUID.randomUUID();
        dispatcher.register(mockNode(nodeId, new AtomicBoolean(false), 200));

        dispatcher.publish(
                StatusReport.newBuilder().setMode("patrol").build(), nodeId, Duration.ofSeconds(1));
        dispatcher.publish(
                StatusReport.newBuilder().setMode("alert").build(), nodeId, Duration.ofSeconds(1));

        ArgumentCaptor<ByteBuffer> captor = ArgumentCaptor.forClass(ByteBuffer.class);
        verify(client, times(2)).publish(captor.capture(), any(Duration.class));
        SapientMessage second = SapientMessage.parseFrom(captor.getAllValues().get(1).array());
        assertEquals(StatusReport.Info.INFO_NEW, second.getStatusReport().getInfo());
        dispatcher.close();
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
