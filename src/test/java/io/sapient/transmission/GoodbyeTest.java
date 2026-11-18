package io.sapient.transmission;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.sapient.testing.FakeEdgeNode;
import io.sapient.testing.FakeFusionNode;
import io.sapient.testing.TestConfig;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import uk.gov.dstl.sapientmsg.bsiflex335v2.StatusReport;

/**
 * A registered node says goodbye when it goes offline, is unregistered, or the dispatcher closes. A
 * node the server never accepted says nothing.
 */
@Execution(ExecutionMode.CONCURRENT)
class GoodbyeTest {

    private final FakeEdgeNode node = new FakeEdgeNode("n1");

    @Test
    void goodbyeSentWhenNodeGoesOffline() {
        var server = new FakeFusionNode();
        try (var dispatcher = server.connect(node)) {
            server.await("n1 status OK new");

            node.goOffline();

            server.await("n1 goodbye new");
        }
    }

    @Test
    void goodbyeCarriesTheNodesStatus() {
        var fault =
                StatusReport.Status.newBuilder()
                        .setStatusLevel(StatusReport.StatusLevel.STATUS_LEVEL_ERROR_STATUS)
                        .setStatusType(StatusReport.StatusType.STATUS_TYPE_INTERNAL_FAULT)
                        .setStatusValue("sensor malfunction")
                        .build();
        var faulty =
                FakeEdgeNode.builder("n1")
                        .withStatusReport(report -> report.addStatus(fault))
                        .build();
        var server = new FakeFusionNode();
        try (var dispatcher = server.connect(faulty)) {
            server.await("n1 status OK new");

            faulty.goOffline();

            StatusReport goodbye = server.await("n1 goodbye new").getStatusReport();
            assertEquals(List.of(fault), goodbye.getStatusList());
        }
    }

    @Test
    void noGoodbyeWhenNodeGoesOfflineAfterRejectedRegistration() {
        var server = FakeFusionNode.builder().withRejectingAllRegistrations().build();
        var config = TestConfig.builder().withRegistrationAckTimeout(Duration.ofMillis(50)).build();
        try (var dispatcher = server.connect(config, node)) {
            node.expect("ack rejected");

            node.goOffline();
            server.awaitDisconnects(1); // the node closes the client after its goodbye step

            // the rejected node may have tried again before it went offline
            server.lines().forEach(line -> assertEquals("n1 registration", line));
        }
    }

    @Test
    void unregisterSendsGoodbyeWhenRegistered() {
        var server = new FakeFusionNode();
        try (var dispatcher = server.connect(node)) {
            server.await("n1 status OK new");

            dispatcher.unregister(node.asNode());

            server.await("n1 goodbye new");
            server.expectNothingMore();
        }
    }

    @Test
    void unregisterNoGoodbyeWhenNotRegistered() {
        var server = FakeFusionNode.builder().withManualRegistrationAnswers().build();
        try (var dispatcher = server.connect(node)) {
            server.expect("n1 registration"); // never answered, so never registered

            dispatcher.unregister(node.asNode());

            server.expectNothingMore();
        }
    }

    @Test
    void closeSendsGoodbyeForRegisteredNodes() {
        var server = new FakeFusionNode();
        var dispatcher = server.connect(node);
        server.await("n1 status OK new");

        dispatcher.close();

        server.await("n1 goodbye new");
        server.expectNothingMore();
    }

    @Test
    void closeNoGoodbyeForUnregisteredNodes() {
        var server = FakeFusionNode.builder().withManualRegistrationAnswers().build();
        var dispatcher = server.connect(node);
        server.expect("n1 registration");

        dispatcher.close();

        server.expectNothingMore();
    }
}
