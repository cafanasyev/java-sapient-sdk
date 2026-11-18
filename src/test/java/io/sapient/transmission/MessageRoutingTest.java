package io.sapient.transmission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.sapient.testing.FakeEdgeNode;
import io.sapient.testing.FakeFusionNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import uk.gov.dstl.sapientmsg.bsiflex335v2.SapientMessage;
import uk.gov.dstl.sapientmsg.bsiflex335v2.Task;
import uk.gov.dstl.sapientmsg.bsiflex335v2.TaskAck;

/** Messages from the server reach the node they are addressed to, or get a rejection back. */
@Execution(ExecutionMode.CONCURRENT)
class MessageRoutingTest {

    private final FakeFusionNode server = new FakeFusionNode();
    private final FakeEdgeNode node = new FakeEdgeNode("n1");
    private final NodeDispatcher dispatcher = server.connect(node);

    @BeforeEach
    void registered() {
        server.expect("n1 registration");
        node.expect("ack accepted");
    }

    @AfterEach
    void close() {
        dispatcher.close();
    }

    @Test
    void alertAckDeliveredToNode() {
        server.sendAlertAck(node, ack -> ack.setAlertId("alert-1"));

        node.expect("alert-ack alert-1");
    }

    @Test
    void errorDeliveredToNode() {
        server.sendError(node, error -> error.addErrorMessage("bad packet"));

        node.expect("error bad packet");
    }

    @Test
    void taskDeliveredToNode() {
        server.sendTask(node, task -> task.setTaskId("task-1"));

        node.expect("task task-1");
    }

    @Test
    void taskWithAnotherRequestDeliveredToNode() {
        server.sendTask(node, task -> task.setTaskId("task-1").setCommand(request("status")));

        node.expect("task task-1");
    }

    @Test
    void registrationRequestInAnyCaseIsHandledByTheDispatcher() {
        server.sendTask(node, task -> task.setTaskId("task-1").setCommand(request("REGISTRATION")));

        // the dispatcher registers the node again, so the node sees a new ack and not the task
        node.expect("ack accepted");
    }

    @Test
    void taskForUnknownNodeIsRejected() {
        var stranger = new FakeEdgeNode("stranger"); // the dispatcher has never heard of it

        server.sendTask(stranger, task -> task.setTaskId("task-1"));

        SapientMessage reply = server.await("stranger task-ack rejected");
        TaskAck ack = reply.getTaskAck();
        assertEquals("task-1", ack.getTaskId());
        assertFalse(ack.getReasonList().isEmpty());
        assertEquals(FakeFusionNode.ID.toString(), reply.getDestinationId());
    }

    private static Task.Command.Builder request(String request) {
        return Task.Command.newBuilder().setRequest(request);
    }
}
