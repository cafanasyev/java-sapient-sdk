package io.sapient.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.sapient.transmission.INode;
import io.sapient.transmission.NodeDispatcherConfig;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import uk.gov.dstl.sapientmsg.bsiflex335v2.AlertAck;
import uk.gov.dstl.sapientmsg.bsiflex335v2.Error;
import uk.gov.dstl.sapientmsg.bsiflex335v2.Registration;
import uk.gov.dstl.sapientmsg.bsiflex335v2.RegistrationAck;
import uk.gov.dstl.sapientmsg.bsiflex335v2.StatusReport;
import uk.gov.dstl.sapientmsg.bsiflex335v2.Task;

@Execution(ExecutionMode.CONCURRENT)
class FakeEdgeNodeTest {

    @Test
    void defaultsAreOnlineWithStatusOk() {
        var node = new FakeEdgeNode("n1");
        INode inode = node.asNode();

        assertTrue(inode.isOnline());
        assertEquals(node.id(), inode.getNodeId());
        assertEquals(StatusReport.System.SYSTEM_OK, inode.getStatusReport().getSystem());
        assertEquals("OK", inode.getStatusReport().getMode());
        assertEquals(
                interval(50), inode.getRegistration().getStatusDefinition().getStatusInterval());
    }

    @Test
    void builderEditsTheMessagesThroughTheProtoBuilders() {
        var node =
                FakeEdgeNode.builder("n1")
                        .withOfflineStart()
                        .withStatusReport(report -> report.setMode("FAULT"))
                        .withStatusInterval(Duration.ofSeconds(1))
                        .build();
        INode inode = node.asNode();

        assertFalse(inode.isOnline());
        assertEquals("FAULT", inode.getStatusReport().getMode());
        assertEquals(
                interval(1000), inode.getRegistration().getStatusDefinition().getStatusInterval());
    }

    @Test
    void callbacksBecomeLines() {
        var node = new FakeEdgeNode("n1");
        INode inode = node.asNode();

        inode.onTask(Task.newBuilder().setTaskId("t1").build());
        inode.onRegistrationAck(RegistrationAck.newBuilder().setAcceptance(true).build());
        inode.onError(Error.newBuilder().addErrorMessage("bad").build());
        inode.onAlertAck(AlertAck.newBuilder().setAlertId("a1").build());

        node.expect("task t1", "ack accepted", "error bad", "alert-ack a1");
    }

    @Test
    void heldOnlineCheckStopsTheCall() throws Exception {
        var node = new FakeEdgeNode("n1");
        Held check = node.holdNext(NodeCall.ONLINE_CHECK);

        var call = Async.run(() -> assertTrue(node.asNode().isOnline()));
        check.awaitArrival();
        call.awaitParkedOrDone();
        assertFalse(call.isDone());

        check.release();
        call.join();
    }

    @Test
    void sendingBeforeConnectFails() {
        var node = new FakeEdgeNode("n1");

        assertThrows(IllegalStateException.class, node::sendStatusReport);
    }

    @Test
    void testConfigHasTheDocumentedDefaults() {
        NodeDispatcherConfig config = TestConfig.defaults();

        assertEquals(Duration.ofMillis(10), config.onlineCheckInterval());
        assertEquals(Duration.ofSeconds(5), config.publishTimeout());
        assertEquals(Duration.ofSeconds(5), config.registrationAckTimeout());
        assertEquals(Duration.ofMinutes(2), config.reconnectGracePeriod());
        assertEquals(Duration.ZERO, config.registrationJitterWindow());
        assertEquals(TestConfig.FUSION_NODE_ID, config.destinationId());
    }

    private static Registration.Duration interval(int millis) {
        return Registration.Duration.newBuilder()
                .setUnits(Registration.TimeUnits.TIME_UNITS_MILLISECONDS)
                .setValue(millis)
                .build();
    }
}
