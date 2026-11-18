package io.sapient.transmission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.sapient.testing.Async;
import io.sapient.testing.FakeEdgeNode;
import io.sapient.testing.FakeFusionNode;
import io.sapient.testing.Held;
import io.sapient.testing.NodeCall;
import io.sapient.testing.TestConfig;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Shutdown behaviour of {@link NodeDispatcher}: when the client closes, and what nodes still send.
 */
@Execution(ExecutionMode.CONCURRENT)
class NodeDispatcherShutdownTest {

    private final FakeFusionNode server = new FakeFusionNode();

    @Test
    void clientClosedExactlyOnceWhenNodesGoOfflineThenDispatcherCloses() {
        var n1 = new FakeEdgeNode("n1");
        var n2 = new FakeEdgeNode("n2");
        var dispatcher = server.connect(n1, n2);
        n1.expect("ack accepted");
        n2.expect("ack accepted");

        n1.goOffline();
        n2.goOffline();
        server.awaitDisconnects(1);
        dispatcher.close();

        assertEquals(1, server.disconnects());
    }

    @Test
    void unregisterClosesClientWhenLastOnlineNodeRemoved() {
        var node = new FakeEdgeNode("n1");
        var dispatcher = server.connect(node);
        server.await("n1 status OK new");

        dispatcher.unregister(node.asNode());
        assertEquals(1, server.disconnects());

        dispatcher.close();
        assertEquals(1, server.disconnects());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nodeCheckedDuringShutdownDoesNotStartTheClient(boolean online) throws Exception {
        var node = FakeEdgeNode.builder("n1").withOfflineStart().build();
        Held check = node.holdNext(NodeCall.ONLINE_CHECK); // a slow check, like a device ping
        var dispatcher = server.connect(node);
        check.awaitArrival();

        var closing = Async.run(dispatcher::close);
        check.awaitInterrupt(); // close() stops the node in the middle of its online check
        if (online) {
            node.goOnline(); // the check reports a node that has just come online
        }
        check.release();
        closing.join();

        assertEquals(0, server.connects());
    }

    @Test
    void nodeCheckedDuringShutdownStopsWithoutWaitingForTheNextCheck() {
        var node = FakeEdgeNode.builder("n1").withOfflineStart().build();
        Held check = node.holdNext(NodeCall.ONLINE_CHECK);
        var config = TestConfig.builder().withOnlineCheckInterval(Duration.ofMinutes(1)).build();
        var dispatcher = server.connect(config, node);
        check.awaitArrival();

        var closing = Async.run(dispatcher::close);
        check.awaitInterrupt();
        check.release();

        // close() gives up on a node thread after 5 s; a node waiting for its next check takes that
        assertTimeoutPreemptively(Duration.ofSeconds(4), closing::join);
    }

    @Test
    void nodeDoesNotPublishAfterClientClosed() throws Exception {
        var node = new FakeEdgeNode("n1");
        Held report = node.holdNext(NodeCall.STATUS_REPORT); // the lifecycle's first report
        var dispatcher = server.connect(node);
        report.awaitArrival();

        var closing = Async.run(dispatcher::close);
        report.awaitInterrupt(); // close() has stopped the node while it builds its report
        closing.awaitParkedOrDone(); // close() waits for the node before it closes the client
        report.release();
        closing.join();

        // the interrupted node sends nothing more; close() sends the goodbye and is done
        server.expect("n1 registration", "n1 goodbye new");
        server.expectNothingMore();
    }
}
