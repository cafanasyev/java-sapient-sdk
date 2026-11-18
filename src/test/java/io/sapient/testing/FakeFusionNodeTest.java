package io.sapient.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.sapient.transport.ConnectionState;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import uk.gov.dstl.sapientmsg.bsiflex335v2.Alert;
import uk.gov.dstl.sapientmsg.bsiflex335v2.SapientMessage;

@Execution(ExecutionMode.CONCURRENT)
class FakeFusionNodeTest {

    private final FakeEdgeNode n1 = new FakeEdgeNode("n1");
    private final FakeEdgeNode quiet = FakeEdgeNode.builder("n1").withOfflineStart().build();

    @Test
    void acceptsRegistrationsByDefault() {
        var server = new FakeFusionNode();
        try (var dispatcher = server.connect(n1)) {
            server.expect("n1 registration");
            n1.expect("ack accepted");
            server.await("n1 status OK new");
        }
    }

    @Test
    void rejectsAllWhenAsked() {
        var server = FakeFusionNode.builder().withRejectingAllRegistrations().build();
        try (var dispatcher = server.connect(n1)) {
            n1.expect("ack rejected");
        }
    }

    @Test
    void rejectsOnlyTheListedNodes() {
        var n2 = new FakeEdgeNode("n2");
        var server = FakeFusionNode.builder().withRejectingRegistrationsFrom(n2).build();
        try (var dispatcher = server.connect(n1, n2)) {
            n1.expect("ack accepted");
            n2.expect("ack rejected");
            server.await("n1 status OK new");
        }
    }

    @Test
    void answersOnlyByHandWhenAsked() {
        var server = FakeFusionNode.builder().withManualRegistrationAnswers().build();
        try (var dispatcher = server.connect(n1)) {
            server.expect("n1 registration");

            server.acceptRegistration(n1);
            n1.expect("ack accepted");
            n1.expectNothingMore();
        }
    }

    @Test
    void failNextLosesTheMessage() throws Exception {
        var server = new FakeFusionNode();
        try (var dispatcher = server.connect(quiet)) {
            server.failNext(new TimeoutException());

            assertThrows(TimeoutException.class, quiet::sendStatusReport);
            quiet.sendStatusReport();
            server.expect("n1 status OK new");
            server.expectNothingMore();
        }
    }

    @Test
    void deliverNextThenFailDeliversIt() {
        var server = new FakeFusionNode();
        try (var dispatcher = server.connect(quiet)) {
            server.deliverNextThenFail(new TimeoutException());

            assertThrows(TimeoutException.class, quiet::sendStatusReport);
            server.expect("n1 status OK new");
        }
    }

    @Test
    void failEveryStatusReportLosesOnlyStatusReports() throws Exception {
        var server = new FakeFusionNode();
        try (var dispatcher = server.connect(quiet)) {
            server.failEveryStatusReport(new TimeoutException());

            assertThrows(TimeoutException.class, quiet::sendStatusReport);
            assertThrows(TimeoutException.class, quiet::sendStatusReport);
            quiet.sendAlert(alert -> {});

            server.expect("n1 alert");
            server.expectNothingMore();
        }
    }

    @Test
    void outageReportsBothEventsWithTheGap() {
        var server = new FakeFusionNode();
        List<Instant> disconnected = new CopyOnWriteArrayList<>();
        List<Instant> connected = new CopyOnWriteArrayList<>();
        server.client()
                .addStateChangeListener(
                        (state, at) ->
                                (state == ConnectionState.CONNECTED ? connected : disconnected)
                                        .add(at));

        server.outage(Duration.ofMinutes(3));

        assertEquals(
                Duration.ofMinutes(3),
                Duration.between(disconnected.getFirst(), connected.getFirst()));
    }

    @Test
    void countsConnectsAndDisconnects() {
        var server = new FakeFusionNode();
        try (var dispatcher = server.connect(n1)) {
            server.awaitConnects(1);

            n1.goOffline();
            server.awaitDisconnects(1);
        }
    }

    @Test
    void messageFromAnUnknownNodeIsNamedByItsId() throws Exception {
        var server = new FakeFusionNode();
        String id = UUID.randomUUID().toString();

        var alert = SapientMessage.newBuilder().setNodeId(id).setAlert(Alert.getDefaultInstance());
        server.client().publish(alert.build(), Duration.ofSeconds(1));

        server.expect(id.substring(0, 8) + " alert");
    }

    @Test
    void holdNextStopsThePublish() throws Exception {
        var server = new FakeFusionNode();
        try (var dispatcher = server.connect(quiet)) {
            Held held = server.holdNext();
            var send = Async.run(quiet::sendStatusReport);
            held.awaitArrival();
            send.awaitParkedOrDone();
            assertFalse(send.isDone());
            server.expectNothingMore();

            held.release();
            send.join();
            server.expect("n1 status OK new");
        }
    }
}
