package io.sapient.transmission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

import io.sapient.transport.ConnectionState;
import io.sapient.transport.IClient;
import io.sapient.transport.SocketClient;
import io.sapient.transport.SocketProvider;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
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
import uk.gov.dstl.sapientmsg.bsiflex335v2.TaskAck;

@Execution(ExecutionMode.CONCURRENT)
class NodeDispatcherTest {

    // --- Utilities ---

    @SuppressWarnings("unchecked")
    static Consumer<SapientMessage> captureSubscription(IClient client) {
        ArgumentCaptor<Consumer<SapientMessage>> captor = ArgumentCaptor.forClass(Consumer.class);
        verify(client).subscribe(captor.capture());
        return captor.getValue();
    }

    static StatusReport isGoodbye() {
        return argThat((StatusReport sr) -> sr.getSystem() == StatusReport.System.SYSTEM_GOODBYE);
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
                                                .setValue((float) statusReportInterval)
                                                .setUnits(
                                                        Registration.TimeUnits
                                                                .TIME_UNITS_MILLISECONDS)))
                .build();
    }

    static final UUID FUSION_NODE_ID = UUID.randomUUID();

    static void sendRegistrationTask(Consumer<SapientMessage> onMessage, UUID nodeId) {
        onMessage.accept(
                SapientMessage.newBuilder()
                        .setDestinationId(nodeId.toString())
                        .setTask(
                                Task.newBuilder()
                                        .setCommand(
                                                Task.Command.newBuilder()
                                                        .setRequest("registration")))
                        .build());
    }

    static void sendAck(Consumer<SapientMessage> onMessage, UUID nodeId, boolean accepted) {
        onMessage.accept(
                SapientMessage.newBuilder()
                        .setNodeId(FUSION_NODE_ID.toString())
                        .setDestinationId(nodeId.toString())
                        .setRegistrationAck(RegistrationAck.newBuilder().setAcceptance(accepted))
                        .build());
    }

    record Setup(
            NodeDispatcher dispatcher,
            IClient client,
            INode node,
            AtomicBoolean online,
            Consumer<SapientMessage> onMessage,
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
                                        Duration.ofMillis(10),
                                        Duration.ofSeconds(5),
                                        Duration.ofSeconds(5),
                                        Duration.ofMinutes(2),
                                        Duration.ZERO,
                                        FUSION_NODE_ID)));
        Consumer<SapientMessage> onMessage = captureSubscription(client);
        return new Setup(dispatcher, client, node, online, onMessage, nodeId);
    }

    static SapientMessage capturePublished(IClient client) throws Exception {
        ArgumentCaptor<SapientMessage> captor = ArgumentCaptor.forClass(SapientMessage.class);
        verify(client).publish(captor.capture(), any(Duration.class));
        return captor.getValue();
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

    static void sendTask(Consumer<SapientMessage> onMessage, UUID nodeId, Task task) {
        onMessage.accept(
                SapientMessage.newBuilder()
                        .setDestinationId(nodeId.toString())
                        .setTask(task)
                        .build());
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
    void rejectedRegistrationDoesNotSpamServer() throws Exception {
        // registrationAckTimeout = 200ms — retry must not arrive before the backoff expires
        UUID nodeId = UUID.randomUUID();
        AtomicBoolean online = new AtomicBoolean(true);
        IClient client = mock(IClient.class);
        INode node = mockNode(nodeId, online, 200);
        NodeDispatcher dispatcher =
                spy(
                        new NodeDispatcher(
                                client,
                                new NodeDispatcherConfig(
                                        Duration.ofMillis(10),
                                        Duration.ofMillis(200),
                                        Duration.ofMillis(200),
                                        Duration.ofMinutes(2),
                                        Duration.ZERO,
                                        FUSION_NODE_ID)));
        Consumer<SapientMessage> onMessage = captureSubscription(client);

        dispatcher.register(node);

        verify(dispatcher, timeout(1000))
                .publish(any(Registration.class), eq(nodeId), any(Duration.class));
        sendAck(onMessage, nodeId, false);

        // immediately after rejection — no second registration yet
        Thread.sleep(50);
        verify(dispatcher, times(1))
                .publish(any(Registration.class), eq(nodeId), any(Duration.class));

        // node must stay in onlineNodes, not moved to offlineNodes
        assertTrue(dispatcher.onlineNodes.containsKey(nodeId));
        assertFalse(dispatcher.offlineNodes.containsKey(nodeId));

        // after the backoff (200ms) the retry arrives
        verify(dispatcher, timeout(1000).atLeast(2))
                .publish(any(Registration.class), eq(nodeId), any(Duration.class));

        dispatcher.close();
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

            verify(s.dispatcher, timeout(1000))
                    .publish(isGoodbye(), eq(s.nodeId), any(Duration.class));
        }
    }

    @Test
    @Timeout(3)
    void goodbyeSetsSystemGoodbyeWhenNotAlreadySet() throws Exception {
        try (var s = setup(50)) {
            when(s.node.getStatusReport()).thenReturn(StatusReport.getDefaultInstance());

            s.online.set(true);
            s.dispatcher.register(s.node);

            verify(s.dispatcher, timeout(1000))
                    .publish(any(Registration.class), eq(s.nodeId), any(Duration.class));
            sendAck(s.onMessage, s.nodeId, true);
            s.online.set(false);

            verify(s.dispatcher, timeout(1000))
                    .publish(isGoodbye(), eq(s.nodeId), any(Duration.class));
        }
    }

    @Test
    @Timeout(3)
    void goodbyePreservesSystemGoodbyeWhenAlreadySet() throws Exception {
        try (var s = setup(50)) {
            when(s.node.getStatusReport())
                    .thenReturn(
                            StatusReport.newBuilder()
                                    .setSystem(StatusReport.System.SYSTEM_GOODBYE)
                                    .build());

            s.online.set(true);
            s.dispatcher.register(s.node);

            verify(s.dispatcher, timeout(1000))
                    .publish(any(Registration.class), eq(s.nodeId), any(Duration.class));
            sendAck(s.onMessage, s.nodeId, true);
            s.online.set(false);

            verify(s.dispatcher, timeout(1000))
                    .publish(isGoodbye(), eq(s.nodeId), any(Duration.class));
        }
    }

    @Test
    @Timeout(3)
    void goodbyeStatusPopulatedFromNodeStatusReport() throws Exception {
        try (var s = setup(50)) {
            StatusReport.Status originalStatus =
                    StatusReport.Status.newBuilder()
                            .setStatusLevel(StatusReport.StatusLevel.STATUS_LEVEL_ERROR_STATUS)
                            .setStatusType(StatusReport.StatusType.STATUS_TYPE_INTERNAL_FAULT)
                            .setStatusValue("sensor malfunction")
                            .build();
            when(s.node.getStatusReport())
                    .thenReturn(StatusReport.newBuilder().addStatus(originalStatus).build());

            s.online.set(true);
            s.dispatcher.register(s.node);

            verify(s.dispatcher, timeout(1000))
                    .publish(any(Registration.class), eq(s.nodeId), any(Duration.class));
            sendAck(s.onMessage, s.nodeId, true);

            verify(s.dispatcher, timeout(1000).atLeastOnce())
                    .publish(any(StatusReport.class), eq(s.nodeId), any(Duration.class));

            s.online.set(false);

            // wait for goodbye to be published, then capture all StatusReport publishes to inspect
            // it
            verify(s.dispatcher, timeout(1000))
                    .publish(isGoodbye(), eq(s.nodeId), any(Duration.class));
            ArgumentCaptor<StatusReport> captor = ArgumentCaptor.forClass(StatusReport.class);
            verify(s.dispatcher, atLeastOnce())
                    .publish(captor.capture(), eq(s.nodeId), any(Duration.class));
            StatusReport goodbyeReport =
                    captor.getAllValues().stream()
                            .filter(sr -> sr.getSystem() == StatusReport.System.SYSTEM_GOODBYE)
                            .findFirst()
                            .orElseThrow();

            assertEquals(StatusReport.System.SYSTEM_GOODBYE, goodbyeReport.getSystem());
            assertEquals(1, goodbyeReport.getStatusCount());
            StatusReport.Status goodbyeStatus = goodbyeReport.getStatus(0);
            assertEquals(
                    StatusReport.StatusLevel.STATUS_LEVEL_ERROR_STATUS,
                    goodbyeStatus.getStatusLevel());
            assertEquals(
                    StatusReport.StatusType.STATUS_TYPE_INTERNAL_FAULT,
                    goodbyeStatus.getStatusType());
            assertEquals("sensor malfunction", goodbyeStatus.getStatusValue());
        }
    }

    @Test
    @Timeout(3)
    void lastStatusReportClearedAfterGoodbye() throws Exception {
        try (var s = setup(50)) {
            s.online.set(true);
            s.dispatcher.register(s.node);

            verify(s.dispatcher, timeout(1000))
                    .publish(any(Registration.class), eq(s.nodeId), any(Duration.class));

            sendAck(s.onMessage, s.nodeId, true);

            verify(s.dispatcher, timeout(1000).atLeastOnce())
                    .publish(any(StatusReport.class), eq(s.nodeId), any(Duration.class));

            s.online.set(false);

            verify(s.dispatcher, timeout(1000))
                    .publish(isGoodbye(), eq(s.nodeId), any(Duration.class));
            Thread.sleep(50);

            assertNull(s.dispatcher.findNode(s.nodeId).getLastStatusReport().get());
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

            verify(s.dispatcher, timeout(1000))
                    .publish(isGoodbye(), eq(s.nodeId), any(Duration.class));

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

            verify(s.dispatcher, timeout(1000))
                    .publish(isGoodbye(), eq(s.nodeId), any(Duration.class));
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

            verify(s.dispatcher, never()).publish(isGoodbye(), any(), any(Duration.class));
        }
    }

    @Test
    @Timeout(3)
    void alertAckDeliveredToNode() throws Exception {
        try (var s = setup(200)) {
            s.online.set(true);
            s.dispatcher.register(s.node);

            AlertAck alertAck = AlertAck.newBuilder().setAlertId("alert-1").build();
            s.onMessage.accept(
                    SapientMessage.newBuilder()
                            .setDestinationId(s.nodeId.toString())
                            .setAlertAck(alertAck)
                            .build());

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

            verify(s.dispatcher, timeout(1000))
                    .publish(any(Registration.class), eq(s.nodeId), any(Duration.class));

            sendTask(s.onMessage, s.nodeId, Task.newBuilder().setTaskId("task-1").build());

            verify(s.node, timeout(1000)).onTask(argThat(t -> "task-1".equals(t.getTaskId())));
        }
    }

    @Test
    @Timeout(3)
    void nonRegistrationRequestTaskPassedToNode() throws Exception {
        try (var s = setup(200)) {
            s.online.set(true);
            s.dispatcher.register(s.node);

            verify(s.dispatcher, timeout(1000))
                    .publish(any(Registration.class), eq(s.nodeId), any(Duration.class));

            sendTask(
                    s.onMessage,
                    s.nodeId,
                    Task.newBuilder()
                            .setTaskId("task-1")
                            .setCommand(Task.Command.newBuilder().setRequest("status"))
                            .build());

            verify(s.node, timeout(1000)).onTask(argThat(t -> "task-1".equals(t.getTaskId())));
        }
    }

    @Test
    @Timeout(3)
    void registrationRequestCaseInsensitiveNotPassedToNode() {
        try (var s = setup(200)) {
            s.dispatcher.register(s.node);

            sendTask(
                    s.onMessage,
                    s.nodeId,
                    Task.newBuilder()
                            .setCommand(Task.Command.newBuilder().setRequest("REGISTRATION"))
                            .build());

            verify(s.node, never()).onTask(any());
        }
    }

    @Test
    @Timeout(3)
    void taskForUnknownNodeIsRejected() throws Exception {
        UUID unknownNodeId = UUID.randomUUID();
        UUID fusionNodeId = UUID.randomUUID();
        IClient client = mock(IClient.class);
        try (var dispatcher =
                new NodeDispatcher(client, NodeDispatcherConfig.defaults(fusionNodeId))) {
            Consumer<SapientMessage> onMessage = captureSubscription(client);

            onMessage.accept(
                    SapientMessage.newBuilder()
                            .setDestinationId(unknownNodeId.toString())
                            .setTask(Task.newBuilder().setTaskId("task-1"))
                            .build());

            SapientMessage sent = capturePublished(client);
            assertEquals(SapientMessage.ContentCase.TASK_ACK, sent.getContentCase());
            assertEquals(
                    TaskAck.TaskStatus.TASK_STATUS_REJECTED, sent.getTaskAck().getTaskStatus());
            assertEquals("task-1", sent.getTaskAck().getTaskId());
            assertFalse(sent.getTaskAck().getReasonList().isEmpty());
            assertEquals(fusionNodeId.toString(), sent.getDestinationId());
            assertEquals(unknownNodeId.toString(), sent.getNodeId());
        }
    }

    @Test
    @Timeout(3)
    void publishRegistrationSerializesToClient() throws Exception {
        IClient client = mock(IClient.class);
        NodeDispatcher dispatcher =
                new NodeDispatcher(client, NodeDispatcherConfig.defaults(FUSION_NODE_ID));
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
        NodeDispatcher dispatcher =
                new NodeDispatcher(client, NodeDispatcherConfig.defaults(FUSION_NODE_ID));
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
        NodeDispatcher dispatcher =
                new NodeDispatcher(client, NodeDispatcherConfig.defaults(FUSION_NODE_ID));
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
        NodeDispatcher dispatcher =
                new NodeDispatcher(client, NodeDispatcherConfig.defaults(FUSION_NODE_ID));
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
    void publishDoesNotForceInfoOnGoodbyeStatusReport() throws Exception {
        IClient client = mock(IClient.class);
        NodeDispatcher dispatcher =
                new NodeDispatcher(client, NodeDispatcherConfig.defaults(FUSION_NODE_ID));
        UUID nodeId = UUID.randomUUID();
        dispatcher.register(mockNode(nodeId, new AtomicBoolean(false), 200));

        StatusReport goodbyeReport =
                StatusReport.newBuilder().setSystem(StatusReport.System.SYSTEM_GOODBYE).build();
        dispatcher.publish(goodbyeReport, nodeId, Duration.ofSeconds(1));

        SapientMessage msg = capturePublished(client);
        assertEquals(StatusReport.System.SYSTEM_GOODBYE, msg.getStatusReport().getSystem());
        assertEquals(StatusReport.Info.INFO_UNSPECIFIED, msg.getStatusReport().getInfo());
        dispatcher.close();
    }

    @Test
    @Timeout(3)
    void publishSetsDestinationIdFromConfig() throws Exception {
        UUID fusionId = UUID.randomUUID();
        IClient client = mock(IClient.class);
        NodeDispatcher dispatcher =
                new NodeDispatcher(client, NodeDispatcherConfig.defaults(fusionId));
        UUID nodeId = UUID.randomUUID();

        dispatcher.publish(StatusReport.getDefaultInstance(), nodeId, Duration.ofSeconds(1));

        SapientMessage msg = capturePublished(client);
        assertEquals(fusionId.toString(), msg.getDestinationId());
        dispatcher.close();
    }

    @Test
    @Timeout(3)
    void statusReportInfoNewOnFirstPublish() throws Exception {
        IClient client = mock(IClient.class);
        NodeDispatcher dispatcher =
                new NodeDispatcher(client, NodeDispatcherConfig.defaults(FUSION_NODE_ID));
        UUID nodeId = UUID.randomUUID();
        dispatcher.register(mockNode(nodeId, new AtomicBoolean(false), 200));

        dispatcher.publish(
                StatusReport.newBuilder().setInfo(StatusReport.Info.INFO_NEW).build(),
                nodeId,
                Duration.ofSeconds(1));

        SapientMessage msg = capturePublished(client);
        assertEquals(StatusReport.Info.INFO_NEW, msg.getStatusReport().getInfo());
        dispatcher.close();
    }

    @Test
    @Timeout(3)
    void statusReportInfoUnchangedOnIdenticalPublish() throws Exception {
        IClient client = mock(IClient.class);
        NodeDispatcher dispatcher =
                new NodeDispatcher(client, NodeDispatcherConfig.defaults(FUSION_NODE_ID));
        UUID nodeId = UUID.randomUUID();
        dispatcher.register(mockNode(nodeId, new AtomicBoolean(false), 200));

        StatusReport report =
                StatusReport.newBuilder()
                        .setMode("patrol")
                        .setInfo(StatusReport.Info.INFO_NEW)
                        .build();
        dispatcher.publish(report, nodeId, Duration.ofSeconds(1));
        dispatcher.publish(report, nodeId, Duration.ofSeconds(1));

        ArgumentCaptor<SapientMessage> captor = ArgumentCaptor.forClass(SapientMessage.class);
        verify(client, times(2)).publish(captor.capture(), any(Duration.class));
        SapientMessage second = captor.getAllValues().get(1);
        assertEquals(StatusReport.Info.INFO_UNCHANGED, second.getStatusReport().getInfo());
        dispatcher.close();
    }

    @Test
    @Timeout(3)
    void statusReportInfoNewOnChangedPublish() throws Exception {
        IClient client = mock(IClient.class);
        NodeDispatcher dispatcher =
                new NodeDispatcher(client, NodeDispatcherConfig.defaults(FUSION_NODE_ID));
        UUID nodeId = UUID.randomUUID();
        dispatcher.register(mockNode(nodeId, new AtomicBoolean(false), 200));

        dispatcher.publish(
                StatusReport.newBuilder()
                        .setMode("patrol")
                        .setInfo(StatusReport.Info.INFO_NEW)
                        .build(),
                nodeId,
                Duration.ofSeconds(1));
        dispatcher.publish(
                StatusReport.newBuilder()
                        .setMode("alert")
                        .setInfo(StatusReport.Info.INFO_NEW)
                        .build(),
                nodeId,
                Duration.ofSeconds(1));

        ArgumentCaptor<SapientMessage> captor = ArgumentCaptor.forClass(SapientMessage.class);
        verify(client, times(2)).publish(captor.capture(), any(Duration.class));
        SapientMessage second = captor.getAllValues().get(1);
        assertEquals(StatusReport.Info.INFO_NEW, second.getStatusReport().getInfo());
        dispatcher.close();
    }

    @Test
    @Timeout(3)
    void registrationRetriedAfterAckTimeout() throws Exception {
        UUID nodeId = UUID.randomUUID();
        AtomicBoolean online = new AtomicBoolean(true);
        IClient client = mock(IClient.class);
        INode node = mockNode(nodeId, online, 200);
        NodeDispatcher dispatcher =
                spy(
                        new NodeDispatcher(
                                client,
                                new NodeDispatcherConfig(
                                        Duration.ofMillis(10),
                                        Duration.ofSeconds(5),
                                        Duration.ofMillis(100),
                                        Duration.ofMinutes(2),
                                        Duration.ZERO,
                                        FUSION_NODE_ID)));
        try (dispatcher) {
            dispatcher.register(node);

            // no ack sent — ack timeout expires — node must retry registration
            verify(dispatcher, timeout(2000).atLeast(2))
                    .publish(any(Registration.class), eq(nodeId), any(Duration.class));
        }
    }

    @Test
    @Timeout(3)
    void nodeRetriesAfterPublishTimeout() throws Exception {
        try (var s = setup(200)) {
            s.online.set(true);

            // first publish throws TimeoutException, subsequent calls succeed
            doThrow(new TimeoutException("test timeout"))
                    .doCallRealMethod()
                    .when(s.dispatcher)
                    .publish(any(Registration.class), eq(s.nodeId), any(Duration.class));

            s.dispatcher.register(s.node);

            // should retry and publish registration at least twice
            verify(s.dispatcher, timeout(2000).atLeast(2))
                    .publish(any(Registration.class), eq(s.nodeId), any(Duration.class));
        }
    }

    @Test
    @Timeout(3)
    void statusReportTimeoutDoesNotTriggerReRegistration() throws Exception {
        try (var s = setup(50)) {
            s.online.set(true);

            // first status report throws TimeoutException, subsequent calls succeed
            doThrow(new TimeoutException("test timeout"))
                    .doCallRealMethod()
                    .when(s.dispatcher)
                    .publish(any(StatusReport.class), eq(s.nodeId), any(Duration.class));

            s.dispatcher.register(s.node);

            verify(s.dispatcher, timeout(1000))
                    .publish(any(Registration.class), eq(s.nodeId), any(Duration.class));

            sendAck(s.onMessage, s.nodeId, true);

            // wait for at least 2 status report attempts (first throws, second succeeds)
            verify(s.dispatcher, timeout(1000).atLeast(2))
                    .publish(any(StatusReport.class), eq(s.nodeId), any(Duration.class));

            // registration must have been sent exactly once — no re-registration triggered
            verify(s.dispatcher, times(1))
                    .publish(any(Registration.class), eq(s.nodeId), any(Duration.class));
        }
    }

    @Test
    @Timeout(3)
    void statusReportTimeoutContinuesSendingReports() throws Exception {
        try (var s = setup(50)) {
            s.online.set(true);

            // first status report throws TimeoutException, subsequent calls succeed
            doThrow(new TimeoutException("test timeout"))
                    .doCallRealMethod()
                    .when(s.dispatcher)
                    .publish(any(StatusReport.class), eq(s.nodeId), any(Duration.class));

            s.dispatcher.register(s.node);

            verify(s.dispatcher, timeout(1000))
                    .publish(any(Registration.class), eq(s.nodeId), any(Duration.class));

            sendAck(s.onMessage, s.nodeId, true);

            // loop must continue — at least 2 status reports sent despite the first timing out
            verify(s.dispatcher, timeout(1000).atLeast(2))
                    .publish(any(StatusReport.class), eq(s.nodeId), any(Duration.class));
        }
    }

    @Test
    @Timeout(3)
    void nodeRetriesAfterRuntimeException() throws Exception {
        try (var s = setup(200)) {
            s.online.set(true);

            // first getRegistration throws, second succeeds
            when(s.node.getRegistration())
                    .thenThrow(new RuntimeException("test error"))
                    .thenReturn(buildRegistration(200));

            s.dispatcher.register(s.node);

            // should retry and publish registration successfully
            verify(s.dispatcher, timeout(2000))
                    .publish(any(Registration.class), eq(s.nodeId), any(Duration.class));
        }
    }

    @Test
    @Timeout(3)
    void registrationTaskResendsRegistrationWhenOnline() throws Exception {
        try (var s = setup(200)) {
            s.online.set(true);
            s.dispatcher.register(s.node);

            verify(s.dispatcher, timeout(1000))
                    .publish(any(Registration.class), eq(s.nodeId), any(Duration.class));
            sendAck(s.onMessage, s.nodeId, true);

            sendRegistrationTask(s.onMessage, s.nodeId);

            // _run() calls getRegistration() once; handleTask() calls it a second time
            verify(s.node, timeout(1000).atLeast(2)).getRegistration();
            // dispatcher consumed the task — node must not receive it via onTask
            verify(s.node, never()).onTask(any());
        }
    }

    @Test
    @Timeout(3)
    void registrationTaskSendsRejectedAckWhenOffline() throws Exception {
        UUID nodeId = UUID.randomUUID();
        IClient client = mock(IClient.class);
        INode node = mockNode(nodeId, new AtomicBoolean(false), 200);
        NodeDispatcher dispatcher =
                new NodeDispatcher(client, NodeDispatcherConfig.defaults(FUSION_NODE_ID));
        dispatcher.register(node);

        Consumer<SapientMessage> onMessage = captureSubscription(client);

        Task task =
                Task.newBuilder()
                        .setTaskId("task-1")
                        .setCommand(Task.Command.newBuilder().setRequest("registration"))
                        .build();
        onMessage.accept(
                SapientMessage.newBuilder()
                        .setDestinationId(nodeId.toString())
                        .setTask(task)
                        .build());

        SapientMessage sent = capturePublished(client);
        assertEquals(SapientMessage.ContentCase.TASK_ACK, sent.getContentCase());
        assertEquals(TaskAck.TaskStatus.TASK_STATUS_REJECTED, sent.getTaskAck().getTaskStatus());
        assertEquals("task-1", sent.getTaskAck().getTaskId());

        dispatcher.close();
    }

    @Test
    @Timeout(3)
    void closeSendsGoodbyeForRegisteredNodes() throws Exception {
        try (var s = setup(200)) {
            s.online.set(true);
            s.dispatcher.register(s.node);

            verify(s.dispatcher, timeout(1000))
                    .publish(any(Registration.class), eq(s.nodeId), any(Duration.class));

            sendAck(s.onMessage, s.nodeId, true);
            Thread.sleep(50); // let registered flag be set

            s.dispatcher.close();

            verify(s.dispatcher).publish(isGoodbye(), eq(s.nodeId), any(Duration.class));
        }
    }

    @Test
    @Timeout(3)
    void closeNoGoodbyeForUnregisteredNodes() throws Exception {
        try (var s = setup(200)) {
            s.online.set(true);
            s.dispatcher.register(s.node);

            verify(s.dispatcher, timeout(1000))
                    .publish(any(Registration.class), eq(s.nodeId), any(Duration.class));

            // no ack sent — node not registered yet
            s.dispatcher.close();

            verify(s.dispatcher, never()).publish(isGoodbye(), any(), any(Duration.class));
        }
    }

    @Test
    @Timeout(3)
    void clientStartedOnlyOnceWhenMultipleNodesOnline() throws Exception {
        UUID nodeId1 = UUID.randomUUID();
        UUID nodeId2 = UUID.randomUUID();
        IClient client = mock(IClient.class);
        INode node1 = mockNode(nodeId1, new AtomicBoolean(true), 200);
        INode node2 = mockNode(nodeId2, new AtomicBoolean(true), 200);
        NodeDispatcher dispatcher =
                spy(
                        new NodeDispatcher(
                                client,
                                new NodeDispatcherConfig(
                                        Duration.ofMillis(10),
                                        Duration.ofSeconds(5),
                                        Duration.ofSeconds(5),
                                        Duration.ofMinutes(2),
                                        Duration.ZERO,
                                        FUSION_NODE_ID)));
        try (dispatcher) {
            dispatcher.register(node1);
            dispatcher.register(node2);

            // wait until both nodes have come online and sent registration
            verify(dispatcher, timeout(1000).atLeastOnce())
                    .publish(any(Registration.class), eq(nodeId1), any(Duration.class));
            verify(dispatcher, timeout(1000).atLeastOnce())
                    .publish(any(Registration.class), eq(nodeId2), any(Duration.class));

            verify(client, times(1)).start();
        }
    }

    @Test
    @Timeout(3)
    void clientStartedWhenFirstNodeComesOnline() throws Exception {
        try (var s = setup(200)) {
            verify(s.client, never()).start();

            s.online.set(true);
            s.dispatcher.register(s.node);

            verify(s.client, timeout(1000)).start();
        }
    }

    @Test
    @Timeout(3)
    void clientClosedWhenLastNodeGoesOffline() throws Exception {
        try (var s = setup(50)) {
            s.online.set(true);
            s.dispatcher.register(s.node);

            verify(s.dispatcher, timeout(1000))
                    .publish(any(Registration.class), eq(s.nodeId), any(Duration.class));
            sendAck(s.onMessage, s.nodeId, true);

            verify(s.dispatcher, timeout(1000).atLeastOnce())
                    .publish(any(StatusReport.class), eq(s.nodeId), any(Duration.class));

            s.online.set(false);

            verify(s.client, timeout(1000)).close();
        }
    }

    @Test
    @Timeout(5)
    void clientNotClosedWhileAnotherNodeStillOnline() throws Exception {
        UUID nodeId1 = UUID.randomUUID();
        UUID nodeId2 = UUID.randomUUID();
        AtomicBoolean online1 = new AtomicBoolean(true);
        AtomicBoolean online2 = new AtomicBoolean(true);
        IClient client = mock(IClient.class);
        INode node1 = mockNode(nodeId1, online1, 50);
        INode node2 = mockNode(nodeId2, online2, 50);
        NodeDispatcher dispatcher =
                spy(
                        new NodeDispatcher(
                                client,
                                new NodeDispatcherConfig(
                                        Duration.ofMillis(10),
                                        Duration.ofSeconds(5),
                                        Duration.ofSeconds(5),
                                        Duration.ofMinutes(2),
                                        Duration.ZERO,
                                        FUSION_NODE_ID)));
        Consumer<SapientMessage> onMessage = captureSubscription(client);

        dispatcher.register(node1);
        dispatcher.register(node2);

        verify(dispatcher, timeout(1000).atLeastOnce())
                .publish(any(Registration.class), eq(nodeId1), any(Duration.class));
        verify(dispatcher, timeout(1000).atLeastOnce())
                .publish(any(Registration.class), eq(nodeId2), any(Duration.class));

        sendAck(onMessage, nodeId1, true);
        sendAck(onMessage, nodeId2, true);

        verify(dispatcher, timeout(1000).atLeastOnce())
                .publish(any(StatusReport.class), eq(nodeId1), any(Duration.class));
        verify(dispatcher, timeout(1000).atLeastOnce())
                .publish(any(StatusReport.class), eq(nodeId2), any(Duration.class));

        // node1 goes offline — node2 still up — client must not close
        online1.set(false);
        verify(dispatcher, timeout(1000)).publish(isGoodbye(), eq(nodeId1), any(Duration.class));
        Thread.sleep(100); // let onNodeOffline complete
        verify(client, never()).close();

        // node2 goes offline — all offline — client must close
        online2.set(false);
        verify(client, timeout(1000)).close();

        dispatcher.close();
    }

    @Test
    @Timeout(5)
    void reRegistrationAfterServerRetentionExpired() throws Exception {
        // serverRetention = 3 × 50ms + 100ms = 250ms
        UUID nodeId = UUID.randomUUID();
        AtomicBoolean online = new AtomicBoolean(true);
        IClient client = mock(IClient.class);
        INode node = mockNode(nodeId, online, 50);
        NodeDispatcher dispatcher =
                spy(
                        new NodeDispatcher(
                                client,
                                new NodeDispatcherConfig(
                                        Duration.ofMillis(10),
                                        Duration.ofSeconds(5),
                                        Duration.ofSeconds(5),
                                        Duration.ofMillis(100),
                                        Duration.ZERO,
                                        FUSION_NODE_ID)));
        Consumer<SapientMessage> onMessage = captureSubscription(client);

        dispatcher.register(node);

        verify(dispatcher, timeout(1000))
                .publish(any(Registration.class), eq(nodeId), any(Duration.class));
        sendAck(onMessage, nodeId, true);

        verify(dispatcher, timeout(1000).atLeast(1))
                .publish(any(StatusReport.class), eq(nodeId), any(Duration.class));

        // simulate connection loss — all status report publishes now time out
        doThrow(new TimeoutException("simulated connection loss"))
                .when(dispatcher)
                .publish(any(StatusReport.class), eq(nodeId), any(Duration.class));

        // after serverRetention (250ms) a second registration must be sent
        verify(dispatcher, timeout(2000).atLeast(2))
                .publish(any(Registration.class), eq(nodeId), any(Duration.class));

        dispatcher.close();
    }

    @SuppressWarnings("unchecked")
    static BiConsumer<ConnectionState, Instant> captureStateListener(IClient client) {
        ArgumentCaptor<BiConsumer<ConnectionState, Instant>> captor =
                ArgumentCaptor.forClass(BiConsumer.class);
        verify(client).addStateChangeListener(captor.capture());
        return captor.getValue();
    }

    @Test
    @Timeout(5)
    void reRegistrationAfterReconnectExceedingGracePeriod() throws Exception {
        // grace period = 100ms; disconnect gap = 150ms → must re-register
        UUID nodeId = UUID.randomUUID();
        AtomicBoolean online = new AtomicBoolean(true);
        IClient client = mock(IClient.class);
        INode node = mockNode(nodeId, online, 50);
        NodeDispatcher dispatcher =
                spy(
                        new NodeDispatcher(
                                client,
                                new NodeDispatcherConfig(
                                        Duration.ofMillis(10),
                                        Duration.ofSeconds(5),
                                        Duration.ofSeconds(5),
                                        Duration.ofMillis(100),
                                        Duration.ZERO,
                                        FUSION_NODE_ID)));
        Consumer<SapientMessage> onMessage = captureSubscription(client);
        BiConsumer<ConnectionState, Instant> stateListener = captureStateListener(client);

        dispatcher.register(node);

        verify(dispatcher, timeout(1000))
                .publish(any(Registration.class), eq(nodeId), any(Duration.class));
        sendAck(onMessage, nodeId, true);
        verify(dispatcher, timeout(1000).atLeast(1))
                .publish(any(StatusReport.class), eq(nodeId), any(Duration.class));

        // simulate: disconnect then reconnect after 150ms (> 100ms grace period)
        Instant lost = Instant.now();
        stateListener.accept(ConnectionState.DISCONNECTED, lost);
        stateListener.accept(ConnectionState.CONNECTED, lost.plusMillis(150));

        verify(dispatcher, timeout(1000).atLeast(2))
                .publish(any(Registration.class), eq(nodeId), any(Duration.class));

        dispatcher.close();
    }

    @Test
    @Timeout(5)
    void reRegistrationAfterOutageInterruptedByFailedReconnectCycles() throws Exception {
        // Regression: SocketClient.runLoop emits DISCONNECTED on every failed reconnect
        // cycle (finally block runs each iteration). If the dispatcher overwrites
        // disconnectedAt on every DISCONNECTED, only the gap between the LAST
        // DISCONNECTED and the eventual CONNECTED is measured — which is tiny and
        // below the grace period, so the epoch would never bump even for a long outage.
        // The real outage here is 200ms (> 100ms grace) and must trigger re-registration.
        UUID nodeId = UUID.randomUUID();
        AtomicBoolean online = new AtomicBoolean(true);
        IClient client = mock(IClient.class);
        INode node = mockNode(nodeId, online, 50);
        NodeDispatcher dispatcher =
                spy(
                        new NodeDispatcher(
                                client,
                                new NodeDispatcherConfig(
                                        Duration.ofMillis(10),
                                        Duration.ofSeconds(5),
                                        Duration.ofSeconds(5),
                                        Duration.ofMillis(100),
                                        Duration.ZERO,
                                        FUSION_NODE_ID)));
        Consumer<SapientMessage> onMessage = captureSubscription(client);
        BiConsumer<ConnectionState, Instant> stateListener = captureStateListener(client);

        dispatcher.register(node);

        verify(dispatcher, timeout(1000))
                .publish(any(Registration.class), eq(nodeId), any(Duration.class));
        sendAck(onMessage, nodeId, true);
        verify(dispatcher, timeout(1000).atLeast(1))
                .publish(any(StatusReport.class), eq(nodeId), any(Duration.class));

        long epochBefore = dispatcher.reregistrationEpoch();

        // Outage spans 200ms (> 100ms grace), broken up by two failed reconnect
        // attempts, each of which emits DISCONNECTED again when the try-with-resources
        // unwinds. Intermediate DISCONNECTED/CONNECTING events must not bump the epoch —
        // only the final CONNECTED event (which crosses the grace period) may bump it.
        Instant lost = Instant.now();
        stateListener.accept(ConnectionState.DISCONNECTED, lost);
        assertEquals(epochBefore, dispatcher.reregistrationEpoch(), "DISCONNECTED must not bump");
        stateListener.accept(ConnectionState.CONNECTING, lost.plusMillis(50));
        assertEquals(epochBefore, dispatcher.reregistrationEpoch(), "CONNECTING must not bump");
        stateListener.accept(ConnectionState.DISCONNECTED, lost.plusMillis(60));
        assertEquals(
                epochBefore,
                dispatcher.reregistrationEpoch(),
                "intermediate DISCONNECTED must not bump");
        stateListener.accept(ConnectionState.CONNECTING, lost.plusMillis(150));
        assertEquals(epochBefore, dispatcher.reregistrationEpoch(), "CONNECTING must not bump");
        stateListener.accept(ConnectionState.DISCONNECTED, lost.plusMillis(160));
        assertEquals(
                epochBefore,
                dispatcher.reregistrationEpoch(),
                "intermediate DISCONNECTED must not bump");
        stateListener.accept(ConnectionState.CONNECTED, lost.plusMillis(200));
        assertEquals(
                epochBefore + 1,
                dispatcher.reregistrationEpoch(),
                "epoch must bump exactly once on CONNECTED crossing the grace period");

        dispatcher.close();
    }

    @Test
    @Timeout(5)
    void noReRegistrationAfterReconnectWithinGracePeriod() throws Exception {
        // grace period = 100ms; disconnect gap = 50ms → must NOT re-register
        UUID nodeId = UUID.randomUUID();
        AtomicBoolean online = new AtomicBoolean(true);
        IClient client = mock(IClient.class);
        INode node = mockNode(nodeId, online, 50);
        NodeDispatcher dispatcher =
                spy(
                        new NodeDispatcher(
                                client,
                                new NodeDispatcherConfig(
                                        Duration.ofMillis(10),
                                        Duration.ofSeconds(5),
                                        Duration.ofSeconds(5),
                                        Duration.ofMillis(100),
                                        Duration.ZERO,
                                        FUSION_NODE_ID)));
        Consumer<SapientMessage> onMessage = captureSubscription(client);
        BiConsumer<ConnectionState, Instant> stateListener = captureStateListener(client);

        dispatcher.register(node);

        verify(dispatcher, timeout(1000))
                .publish(any(Registration.class), eq(nodeId), any(Duration.class));
        sendAck(onMessage, nodeId, true);
        verify(dispatcher, timeout(1000).atLeast(1))
                .publish(any(StatusReport.class), eq(nodeId), any(Duration.class));

        // simulate: disconnect then reconnect after 50ms (< 100ms grace period)
        Instant lost = Instant.now();
        stateListener.accept(ConnectionState.DISCONNECTED, lost);
        stateListener.accept(ConnectionState.CONNECTED, lost.plusMillis(50));

        // only the original registration — no re-registration within 500ms
        Thread.sleep(500);
        verify(dispatcher, times(1))
                .publish(any(Registration.class), eq(nodeId), any(Duration.class));

        dispatcher.close();
    }

    @Test
    @Timeout(10)
    void reRegistrationWhenDetectionDelayPushesGapOverGracePeriod() throws Exception {
        // Closing server1 with its accepted socket still alive leaves readLoop blocked on
        // a half-open TCP connection. The SocketClient watchdog (100ms interval) is the only
        // thing that can detect the server is gone: next probe fails → watchdog closes the
        // socket → readLoop wakes → DISCONNECTED. On reconnect, connectionLossDetectionDelay
        // (150ms) added to the raw gap crosses the 100ms grace → epoch bumps → re-register.
        UUID nodeId = UUID.randomUUID();
        INode node = mockNode(nodeId, new AtomicBoolean(true), 50);

        ServerSocket server1 = new ServerSocket(0);
        AtomicInteger port = new AtomicInteger(server1.getLocalPort());
        SocketClient client =
                spy(
                        new SocketClient(
                                socketProvider(port),
                                Duration.ofMillis(200),
                                Duration.ofMillis(10),
                                Duration.ofMillis(100)));
        LinkedBlockingQueue<ConnectionState> states = new LinkedBlockingQueue<>();
        client.addStateChangeListener((s, ts) -> states.add(s));
        BlockingQueue<SapientMessage> registrations = new LinkedBlockingQueue<>();
        Thread.startVirtualThread(() -> runTestServer(server1, nodeId, registrations));

        try (NodeDispatcher dispatcher =
                        new NodeDispatcher(
                                client,
                                new NodeDispatcherConfig(
                                        Duration.ofMillis(10),
                                        Duration.ofSeconds(5),
                                        Duration.ofSeconds(5),
                                        Duration.ofMillis(100),
                                        Duration.ofMillis(150),
                                        FUSION_NODE_ID));
                ServerSocket server2 = new ServerSocket(0)) {
            dispatcher.register(node);
            assertNotNull(registrations.poll(3, TimeUnit.SECONDS), "initial registration");
            long epochBefore = dispatcher.reregistrationEpoch();

            server1.close();
            assertTrue(
                    drainUntil(states, ConnectionState.DISCONNECTED),
                    "expected DISCONNECTED state");
            // the watchdog is the sole driver of disconnect detection on a half-open socket —
            // asserting it ran at least once confirms the expected path was exercised
            verify(client, timeout(3000).atLeastOnce()).probeReachable(any(Duration.class));

            Thread.startVirtualThread(() -> runTestServer(server2, nodeId, registrations));
            port.set(server2.getLocalPort());

            assertNotNull(
                    registrations.poll(5, TimeUnit.SECONDS), "re-registration after reconnect");
            assertTrue(dispatcher.reregistrationEpoch() > epochBefore, "epoch should increment");
        }
    }

    private static SocketProvider socketProvider(AtomicInteger port) {
        return new SocketProvider() {
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

    @Test
    @Timeout(5)
    void clientRestartedAfterAllNodesOffline() throws Exception {
        try (var s = setup(50)) {
            s.online.set(true);
            s.dispatcher.register(s.node);

            verify(s.dispatcher, timeout(1000))
                    .publish(any(Registration.class), eq(s.nodeId), any(Duration.class));
            sendAck(s.onMessage, s.nodeId, true);

            verify(s.dispatcher, timeout(1000).atLeastOnce())
                    .publish(any(StatusReport.class), eq(s.nodeId), any(Duration.class));

            // go offline → client closes
            s.online.set(false);
            verify(s.client, timeout(1000)).close();

            // new node comes online → client starts again
            UUID nodeId2 = UUID.randomUUID();
            AtomicBoolean online2 = new AtomicBoolean(true);
            s.dispatcher.register(mockNode(nodeId2, online2, 200));

            verify(s.client, timeout(2000).times(2)).start();
        }
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
                                    .setNodeId(FUSION_NODE_ID.toString())
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
