package io.sapient.transmission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

import io.sapient.transport.IClient;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
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
                                                .setValue(statusReportInterval)
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
}
