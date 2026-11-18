package io.sapient.transmission;

import io.sapient.testing.FakeEdgeNode;
import io.sapient.testing.FakeFusionNode;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** A failed publish does not stop a node: it registers again, or carries on reporting. */
@Execution(ExecutionMode.CONCURRENT)
class PublishFailureTest {

    private final FakeEdgeNode node = new FakeEdgeNode("n1");

    @ParameterizedTest
    @MethodSource("failures")
    void registrationRetriedAfterFailedPublish(Exception failure) {
        var server = new FakeFusionNode();
        server.failNext(failure);
        try (var dispatcher = server.connect(node)) {
            server.expect("n1 registration"); // the second try: the first one never arrived
            node.expect("ack accepted");
        }
    }

    /** A publish that ran out of time, and one that failed in a way nobody planned for. */
    static Stream<Exception> failures() {
        return Stream.of(new TimeoutException(), new IllegalStateException());
    }

    @Test
    void statusReportTimeoutDoesNotTriggerReRegistration() {
        var server = FakeFusionNode.builder().withManualRegistrationAnswers().build();
        try (var dispatcher = server.connect(node)) {
            server.expect("n1 registration");
            server.failNext(new TimeoutException()); // the first status report
            server.acceptRegistration(node);

            // the reports carry on, and no registration comes between them
            server.expect("n1 status OK new", "n1 status OK unchanged");
        }
    }
}
