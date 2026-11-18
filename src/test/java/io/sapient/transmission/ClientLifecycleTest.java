package io.sapient.transmission;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.sapient.testing.FakeEdgeNode;
import io.sapient.testing.FakeFusionNode;
import io.sapient.testing.Held;
import io.sapient.testing.NodeCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/** The dispatcher runs the client while at least one node is online, and only then. */
@Execution(ExecutionMode.CONCURRENT)
class ClientLifecycleTest {

    private final FakeFusionNode server = new FakeFusionNode();

    @Test
    void clientStartedWhenFirstNodeComesOnline() {
        var node = FakeEdgeNode.builder("n1").withOfflineStart().build();
        try (var dispatcher = server.connect(node)) {
            assertEquals(0, server.connects());

            node.goOnline();

            server.awaitConnects(1);
        }
    }

    @Test
    void clientStartedOnlyOnceWhenMultipleNodesOnline() {
        var n1 = new FakeEdgeNode("n1");
        var n2 = new FakeEdgeNode("n2");
        try (var dispatcher = server.connect(n1, n2)) {
            n1.expect("ack accepted");
            n2.expect("ack accepted");

            assertEquals(1, server.connects());
        }
    }

    @Test
    void clientClosedWhenLastNodeGoesOffline() {
        var node = new FakeEdgeNode("n1");
        try (var dispatcher = server.connect(node)) {
            server.await("n1 status OK new");

            node.goOffline();

            server.awaitDisconnects(1);
        }
    }

    @Test
    void clientNotClosedWhileAnotherNodeStillOnline() {
        var n1 = new FakeEdgeNode("n1");
        var n2 = new FakeEdgeNode("n2");
        try (var dispatcher = server.connect(n1, n2)) {
            n1.expect("ack accepted");
            n2.expect("ack accepted");

            n1.goOffline();
            server.await("n1 goodbye new");
            awaitTakenOffline(n1);
            assertEquals(0, server.disconnects());

            n2.goOffline();
            server.awaitDisconnects(1);
        }
    }

    @Test
    void clientRestartedAfterAllNodesOffline() {
        var n1 = new FakeEdgeNode("n1");
        try (var dispatcher = server.connect(n1)) {
            server.await("n1 status OK new");
            n1.goOffline();
            server.awaitDisconnects(1);

            server.register(dispatcher, new FakeEdgeNode("n2"));

            server.awaitConnects(2);
        }
    }

    /**
     * Returns once the dispatcher has taken offline a node that has just said goodbye. The node's
     * first online check after the goodbye may still come before that; the second comes after.
     */
    private static void awaitTakenOffline(FakeEdgeNode node) {
        Held first = node.holdNext(NodeCall.ONLINE_CHECK);
        first.awaitArrival();
        Held second = node.holdNext(NodeCall.ONLINE_CHECK);
        first.release();
        second.awaitArrival();
        second.release();
    }
}
