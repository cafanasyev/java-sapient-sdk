package io.sapient.transmission;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static uk.gov.dstl.sapientmsg.bsiflex335v2.StatusReport.Info.INFO_UNCHANGED;
import static uk.gov.dstl.sapientmsg.bsiflex335v2.StatusReport.System.SYSTEM_GOODBYE;

import io.sapient.testing.Async;
import io.sapient.testing.FakeEdgeNode;
import io.sapient.testing.FakeFusionNode;
import io.sapient.testing.Held;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import uk.gov.dstl.sapientmsg.bsiflex335v2.StatusReport;

/**
 * An UNCHANGED status report tells the server that nothing changed since the previous report. So an
 * UNCHANGED report must repeat the last report the server received. The tests check this rule for
 * single reports, when reports of one node race, and after events that leave the server's last
 * report unknown.
 */
@Execution(ExecutionMode.CONCURRENT)
class StatusReportInfoTest {

    private final FakeFusionNode server = new FakeFusionNode();
    // offline: the node's own lifecycle sends nothing, only the test does
    private final FakeEdgeNode node = FakeEdgeNode.builder("n1").withOfflineStart().build();
    private final NodeDispatcher dispatcher = server.connect(node);

    @AfterEach
    void close() {
        dispatcher.close();
    }

    @ParameterizedTest
    @EnumSource(names = {"INFO_NEW", "INFO_UNSPECIFIED"})
    void firstReportIsNew(StatusReport.Info info) throws Exception {
        node.sendStatusReport(report -> report.setInfo(info));

        server.expect("n1 status OK new");
    }

    @ParameterizedTest
    @EnumSource(names = {"INFO_NEW", "INFO_UNSPECIFIED"})
    void repeatedReportIsUnchanged(StatusReport.Info info) throws Exception {
        node.sendStatusReport(report -> report.setInfo(info));
        node.sendStatusReport(report -> report.setInfo(info));

        server.expect("n1 status OK new", "n1 status OK unchanged");
    }

    @ParameterizedTest
    @EnumSource(names = {"INFO_NEW", "INFO_UNSPECIFIED"})
    void changedReportIsNew(StatusReport.Info info) throws Exception {
        node.sendStatusReport(report -> report.setInfo(info));
        node.sendStatusReport(report -> report.setMode("FAULT").setInfo(info));

        server.expect("n1 status OK new", "n1 status FAULT new");
    }

    @Test
    void explicitUnchangedIsKept() throws Exception {
        node.sendStatusReport(report -> report.setInfo(INFO_UNCHANGED));

        server.expect("n1 status OK unchanged");
    }

    @Test
    void explicitUnchangedReportIsTheNextBaseline() throws Exception {
        node.sendStatusReport(report -> report.setInfo(INFO_UNCHANGED));
        node.sendStatusReport();

        server.expect("n1 status OK unchanged", "n1 status OK unchanged");
    }

    @Test
    void reportOfAnUnregisteredNodeIsAlwaysNew() throws Exception {
        UUID stranger = UUID.randomUUID();
        StatusReport report = node.asNode().getStatusReport();

        dispatcher.publish(report, stranger, Duration.ofSeconds(5));
        dispatcher.publish(report, stranger, Duration.ofSeconds(5));

        String name = stranger.toString().substring(0, 8);
        server.expect(name + " status OK new", name + " status OK new");
    }

    @Test
    void laterReportDoesNotOvertakeEarlierOne() throws Exception {
        node.sendStatusReport();

        Held ok = server.holdNext();
        var okSent = Async.run(node::sendStatusReport);
        ok.awaitArrival();
        var faultSent = Async.run(() -> node.sendStatusReport(report -> report.setMode("FAULT")));
        faultSent.awaitParkedOrDone(); // FAULT either reaches the server first or waits for OK
        ok.release();
        Async.joinAll(okSent, faultSent);

        server.expect("n1 status OK new", "n1 status OK unchanged", "n1 status FAULT new");
    }

    @Test
    void nextReportIsComparedWithLastOneSent() throws Exception {
        node.sendStatusReport();

        Held degraded = server.holdNext();
        Held fault = server.holdNext();
        var degradedSent = Async.run(() -> node.sendStatusReport(r -> r.setMode("DEGRADED")));
        degraded.awaitArrival();
        var faultSent = Async.run(() -> node.sendStatusReport(r -> r.setMode("FAULT")));
        faultSent.awaitParkedOrDone(); // FAULT is held as well, or waits for DEGRADED
        degraded.release();
        degradedSent.join();
        fault.release();
        faultSent.join();
        node.sendStatusReport(report -> report.setMode("DEGRADED"));

        server.expect(
                "n1 status OK new",
                "n1 status DEGRADED new",
                "n1 status FAULT new",
                "n1 status DEGRADED new");
    }

    @Test
    void reportWaitingForAnotherGivesUpAtItsTimeout() throws Exception {
        Held fault = server.holdNext();
        var faultSent = Async.run(() -> node.sendStatusReport(r -> r.setMode("FAULT")));
        fault.awaitArrival();

        StatusReport ok = node.asNode().getStatusReport();
        assertThrows(
                TimeoutException.class,
                () -> dispatcher.publish(ok, node.id(), Duration.ofMillis(200)));
        fault.release();
        faultSent.join();
        node.sendStatusReport();

        server.expect("n1 status FAULT new", "n1 status OK new");
    }

    @Test
    void reportsOfDifferentNodesDoNotWaitForEachOther() throws Exception {
        var other = FakeEdgeNode.builder("n2").withOfflineStart().build();
        server.register(dispatcher, other);

        Held fault = server.holdNext();
        var faultSent = Async.run(() -> node.sendStatusReport(r -> r.setMode("FAULT")));
        fault.awaitArrival();
        dispatcher.publish(other.asNode().getStatusReport(), other.id(), Duration.ofMillis(200));
        fault.release();
        faultSent.join();

        server.expect("n2 status OK new", "n1 status FAULT new");
    }

    @ParameterizedTest
    @MethodSource("failures")
    void reportAfterFailedPublishIsNew(Exception failure) throws Exception {
        node.sendStatusReport();

        server.deliverNextThenFail(failure);
        assertThrows(failure.getClass(), () -> node.sendStatusReport(r -> r.setMode("FAULT")));
        node.sendStatusReport();

        server.expect("n1 status OK new", "n1 status FAULT new", "n1 status OK new");
    }

    /** Failures after which the dispatcher cannot know whether the server got the report. */
    static Stream<Exception> failures() {
        return Stream.of(
                new TimeoutException(), new InterruptedException(), new IllegalStateException());
    }

    @Test
    void reportAfterLostPublishIsNew() throws Exception {
        node.sendStatusReport();

        server.failNext(new TimeoutException());
        assertThrows(TimeoutException.class, node::sendStatusReport);
        node.sendStatusReport();

        server.expect("n1 status OK new", "n1 status OK new");
    }

    @Test
    void goodbyeIsNewAndSoIsTheReportAfterIt() throws Exception {
        node.sendStatusReport();
        node.sendStatusReport(report -> report.setSystem(SYSTEM_GOODBYE));
        node.sendStatusReport();

        server.expect("n1 status OK new", "n1 goodbye new", "n1 status OK new");
    }

    @Test
    void reportAfterRegistrationIsNew() throws Exception {
        node.sendStatusReport();
        node.sendRegistration();
        node.sendStatusReport();

        server.expect("n1 status OK new", "n1 registration", "n1 status OK new");
    }

    @Test
    void reportAfterReconnectIsNew() throws Exception {
        node.sendStatusReport();
        server.outage(Duration.ofSeconds(1));
        node.sendStatusReport();

        server.expect("n1 status OK new", "n1 status OK new");
    }
}
