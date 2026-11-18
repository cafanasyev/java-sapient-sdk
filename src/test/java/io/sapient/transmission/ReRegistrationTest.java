package io.sapient.transmission;

import static io.sapient.transport.ConnectionState.CONNECTED;
import static io.sapient.transport.ConnectionState.CONNECTING;
import static io.sapient.transport.ConnectionState.DISCONNECTED;

import io.sapient.testing.FakeEdgeNode;
import io.sapient.testing.FakeFusionNode;
import io.sapient.testing.Held;
import io.sapient.testing.NodeCall;
import io.sapient.testing.TestConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * A registered node registers again when the server may have forgotten it: after it was offline,
 * after an outage longer than the grace period, or when no status report got through for longer
 * than the server keeps a registration. The test config's grace period is 2 minutes.
 */
@Execution(ExecutionMode.CONCURRENT)
class ReRegistrationTest {

    private final FakeFusionNode server = new FakeFusionNode();
    private final FakeEdgeNode node = new FakeEdgeNode("n1");

    @Test
    void reRegistrationAfterOfflineOnline() {
        try (var dispatcher = server.connect(node)) {
            server.await("n1 status OK new");

            node.goOffline();
            server.await("n1 goodbye new");
            node.goOnline();

            server.expect("n1 registration", "n1 status OK new");
        }
    }

    @Test
    void reRegistrationAfterServerRetentionExpired() {
        // the server keeps a registration for about 3 status intervals plus the grace period;
        // a short grace period keeps that window, and so the test, about a second long
        var config = TestConfig.builder().withReconnectGracePeriod(Duration.ofSeconds(1)).build();
        try (var dispatcher = server.connect(config, node)) {
            server.await("n1 status OK new");

            server.failEveryStatusReport(new TimeoutException());

            server.await("n1 registration");
        }
    }

    @Test
    void reRegistrationAfterReconnectExceedingGracePeriod() {
        try (var dispatcher = server.connect(node)) {
            server.await("n1 status OK new");

            server.outage(Duration.ofMinutes(3));

            server.await("n1 registration");
        }
    }

    @Test
    void reRegistrationAfterOutageInterruptedByFailedReconnectCycles() {
        try (var dispatcher = server.connect(node)) {
            server.await("n1 status OK new");

            // every failed reconnect reports DISCONNECTED again; the outage still counts from
            // the first one, 3 minutes before CONNECTED, and not from the last, 89 seconds before
            Instant lost = Instant.now();
            server.reportConnectionState(DISCONNECTED, lost);
            server.reportConnectionState(CONNECTING, lost.plusSeconds(30));
            server.reportConnectionState(DISCONNECTED, lost.plusSeconds(31));
            server.reportConnectionState(CONNECTING, lost.plusSeconds(90));
            server.reportConnectionState(DISCONNECTED, lost.plusSeconds(91));
            server.reportConnectionState(CONNECTED, lost.plus(Duration.ofMinutes(3)));

            server.await("n1 registration");
        }
    }

    @Test
    void noReRegistrationAfterReconnectWithinGracePeriod() {
        try (var dispatcher = server.connect(node)) {
            server.await("n1 status OK new");
            Held report = node.holdNext(NodeCall.STATUS_REPORT);
            // this round of the status loop has already looked for a reason to re-register
            report.awaitArrival();

            server.outage(Duration.ofMinutes(1));
            report.release();

            // the held report is the first on the new connection; the next round comes after the
            // outage, and a re-registration would show up there
            server.await("n1 status OK new");
            server.expect("n1 status OK unchanged");
        }
    }
}
