package io.sapient.transmission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.Timestamp;
import io.sapient.testing.FakeEdgeNode;
import io.sapient.testing.FakeFusionNode;
import io.sapient.testing.TestConfig;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import uk.gov.dstl.sapientmsg.bsiflex335v2.Task;
import uk.gov.dstl.sapientmsg.bsiflex335v2.TaskAck;

/** How a node registers: when it starts, how it retries, and what a registration task does. */
@Execution(ExecutionMode.CONCURRENT)
class RegistrationTest {

    private final FakeEdgeNode node = new FakeEdgeNode("n1");

    @Test
    void registrationSentWhenNodeComesOnline() {
        var server = new FakeFusionNode();
        try (var dispatcher = server.connect(node)) {
            server.expect("n1 registration");
        }
    }

    @Test
    void registrationAckDeliveredToNode() {
        var server = new FakeFusionNode();
        try (var dispatcher = server.connect(node)) {
            server.expect("n1 registration");
            node.expect("ack accepted");
        }
    }

    @Test
    void statusReportsSentAfterAcceptedAck() {
        var server = new FakeFusionNode();
        try (var dispatcher = server.connect(node)) {
            server.expect("n1 registration", "n1 status OK new", "n1 status OK unchanged");
        }
    }

    @Test
    void unansweredRegistrationIsRetried() {
        var server = FakeFusionNode.builder().withManualRegistrationAnswers().build();
        var config = TestConfig.builder().withRegistrationAckTimeout(Duration.ofMillis(50)).build();
        try (var dispatcher = server.connect(config, node)) {
            // a status report sent before the ack would show up between the two
            server.expect("n1 registration", "n1 registration");
        }
    }

    @Test
    void rejectedRegistrationDoesNotSpamServer() {
        var server = FakeFusionNode.builder().withRejectingAllRegistrations().build();
        var config =
                TestConfig.builder().withRegistrationAckTimeout(Duration.ofMillis(200)).build();
        try (var dispatcher = server.connect(config, node)) {
            Instant first = sentAt(server.next().getTimestamp());
            node.expect("ack rejected");
            Instant retry = sentAt(server.next().getTimestamp());

            // a rejected node waits one ack timeout before it registers again
            assertTrue(Duration.between(first, retry).compareTo(Duration.ofMillis(200)) >= 0);
            // and it stays online, so the client stays connected
            assertEquals(0, server.disconnects());
        }
    }

    @Test
    void registrationTaskResendsRegistrationWhenOnline() {
        var server = new FakeFusionNode();
        try (var dispatcher = server.connect(node)) {
            node.expect("ack accepted");

            server.requestRegistration(node);
            server.await("n1 registration");
            // the dispatcher handles the task itself; a task passed to the node would come first
            node.expect("ack accepted");
        }
    }

    @Test
    void taskDrivenReregistrationAckDeliveredToNode() {
        var server = new FakeFusionNode();
        try (var dispatcher = server.connect(node)) {
            server.await("n1 status OK new");

            // a second one used to find the ack queue still full from the first, and lose its ack
            for (int i = 0; i < 2; i++) {
                server.requestRegistration(node);
                server.await("n1 registration");
            }

            node.expect("ack accepted", "ack accepted", "ack accepted");
        }
    }

    @Test
    void rejectedTaskDrivenReregistrationReentersRegister() {
        var server = FakeFusionNode.builder().withManualRegistrationAnswers().build();
        try (var dispatcher = server.connect(node)) {
            server.expect("n1 registration");
            server.acceptRegistration(node);
            server.await("n1 status OK new");

            server.requestRegistration(node);
            server.await("n1 registration");
            server.rejectRegistration(node);

            // the status loop ends and the node registers again by itself
            server.await("n1 registration");
        }
    }

    @Test
    void registrationTaskSendsRejectedAckWhenOffline() {
        var offline = FakeEdgeNode.builder("n1").withOfflineStart().build();
        var server = new FakeFusionNode();
        try (var dispatcher = server.connect(offline)) {
            server.sendTask(
                    offline,
                    task ->
                            task.setTaskId("task-1")
                                    .setCommand(
                                            Task.Command.newBuilder().setRequest("registration")));

            TaskAck ack = server.next().getTaskAck();
            assertEquals(TaskAck.TaskStatus.TASK_STATUS_REJECTED, ack.getTaskStatus());
            assertEquals("task-1", ack.getTaskId());
        }
    }

    private static Instant sentAt(Timestamp timestamp) {
        return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
    }
}
