package io.sapient.transmission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.github.f4b6a3.ulid.Ulid;
import com.google.protobuf.Timestamp;
import io.sapient.testing.FakeEdgeNode;
import io.sapient.testing.FakeFusionNode;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;
import uk.gov.dstl.sapientmsg.bsiflex335v2.SapientMessage;
import uk.gov.dstl.sapientmsg.bsiflex335v2.StatusReport;

/** What the dispatcher adds to a node's message before it goes to the server. */
@Execution(ExecutionMode.CONCURRENT)
class PublishTest {

    private static final String ULID = "01ARZ3NDEKTSV4RRFFQ69G5FAV";

    private final FakeFusionNode server = new FakeFusionNode();
    // offline: the node's own lifecycle sends nothing, only the test does
    private final FakeEdgeNode node = FakeEdgeNode.builder("n1").withOfflineStart().build();
    private final NodeDispatcher dispatcher = server.connect(node);

    @AfterEach
    void close() {
        dispatcher.close();
    }

    @Test
    void everyMessageSaysWhoSentItToWhomAndWhen() throws Exception {
        Instant before = Instant.now();
        List<SapientMessage> sent =
                List.of(
                        node.sendRegistration(),
                        node.sendStatusReport(),
                        node.sendAlert(alert -> {}),
                        node.sendDetection(detection -> {}));
        Instant after = Instant.now();

        server.expect("n1 registration", "n1 status OK new", "n1 alert", "n1 detection");
        for (SapientMessage message : sent) {
            assertEquals(node.id().toString(), message.getNodeId());
            assertEquals(FakeFusionNode.ID.toString(), message.getDestinationId());
            Instant at = sentAt(message.getTimestamp());
            assertTrue(!at.isBefore(before) && !at.isAfter(after), at + " is outside the call");
        }
    }

    @Test
    void statusReportWithoutReportIdGetsOne() throws Exception {
        String id = node.sendStatusReport().getStatusReport().getReportId();

        assertTrue(Ulid.isValid(id), id);
    }

    @Test
    void statusReportKeepsItsReportId() throws Exception {
        var sent = node.sendStatusReport(report -> report.setReportId(ULID));

        assertEquals(ULID, sent.getStatusReport().getReportId());
    }

    @Test
    void detectionWithoutReportIdGetsOne() throws Exception {
        String id = node.sendDetection(detection -> {}).getDetectionReport().getReportId();

        assertTrue(Ulid.isValid(id), id);
    }

    @Test
    void detectionKeepsItsReportId() throws Exception {
        var sent = node.sendDetection(detection -> detection.setReportId(ULID));

        assertEquals(ULID, sent.getDetectionReport().getReportId());
    }

    @Test
    void alertWithoutAlertIdGetsOne() throws Exception {
        String id = node.sendAlert(alert -> {}).getAlert().getAlertId();

        assertTrue(Ulid.isValid(id), id);
    }

    @Test
    void alertKeepsItsAlertId() throws Exception {
        var sent = node.sendAlert(alert -> alert.setAlertId(ULID));

        assertEquals(ULID, sent.getAlert().getAlertId());
    }

    @Test
    void logBodyRendersMessageAsJson() {
        Logger log = mock(Logger.class);
        SapientMessage message =
                SapientMessage.newBuilder()
                        .setStatusReport(StatusReport.newBuilder().setMode("patrol"))
                        .build();

        NodeDispatcher.logBody(log, message);

        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(log).debug(eq("message: {}"), body.capture());
        assertTrue(String.valueOf(body.getValue()).contains("\"mode\": \"patrol\""));
    }

    private static Instant sentAt(Timestamp timestamp) {
        return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
    }
}
