package io.sapient.transmission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.sapient.transport.ConnectionState;
import io.sapient.transport.IClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.stubbing.Answer;
import uk.gov.dstl.sapientmsg.bsiflex335v2.Registration;
import uk.gov.dstl.sapientmsg.bsiflex335v2.SapientMessage;
import uk.gov.dstl.sapientmsg.bsiflex335v2.StatusReport;

/**
 * An UNCHANGED status report tells the server that nothing changed since the previous report. So an
 * UNCHANGED report must repeat the last report the server received. The tests check this rule when
 * reports of one node race, and after events that leave the server's last report unknown.
 */
@Timeout(10)
@Execution(ExecutionMode.CONCURRENT)
class StatusReportInfoTest {

    private static final UUID NODE_ID = UUID.randomUUID();
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private Server server;
    private NodeDispatcher dispatcher;

    @BeforeEach
    void setUp() throws Exception {
        server = new Server();
        dispatcher =
                new NodeDispatcher(server.client, NodeDispatcherConfig.defaults(UUID.randomUUID()));
        register(NODE_ID);
    }

    @AfterEach
    void tearDown() {
        dispatcher.close();
    }

    @Test
    void laterReportDoesNotOvertakeEarlierOne() throws Exception {
        publish("OK");

        Hold ok = server.holdNext();
        Future<?> okSent = publishInBackground("OK");
        ok.awaitArrival();
        Future<?> faultSent = publishInBackground("FAULT");
        Thread.sleep(200); // FAULT gets 200 ms to reach the server ahead of the held OK
        ok.release();
        okSent.get();
        faultSent.get();

        assertEquals(List.of("OK new", "OK unchanged", "FAULT new"), server.received);
    }

    @Test
    void nextReportIsComparedWithLastOneSent() throws Exception {
        publish("OK");

        Hold degraded = server.holdNext();
        Hold fault = server.holdNext();
        Future<?> degradedSent = publishInBackground("DEGRADED");
        degraded.awaitArrival();
        Future<?> faultSent = publishInBackground("FAULT");
        Thread.sleep(200); // FAULT gets 200 ms to get held as well
        degraded.release();
        degradedSent.get();
        fault.release();
        faultSent.get();
        publish("DEGRADED");

        assertEquals(
                List.of("OK new", "DEGRADED new", "FAULT new", "DEGRADED new"), server.received);
    }

    @Test
    void reportWaitingForAnotherGivesUpAtItsTimeout() throws Exception {
        Hold fault = server.holdNext();
        Future<?> faultSent = publishInBackground("FAULT");
        fault.awaitArrival();

        assertThrows(
                TimeoutException.class,
                () -> dispatcher.publish(report("OK"), NODE_ID, Duration.ofMillis(200)));
        fault.release();
        faultSent.get();
        publish("OK");

        assertEquals(List.of("FAULT new", "OK new"), server.received);
    }

    @Test
    void reportsOfDifferentNodesDoNotWaitForEachOther() throws Exception {
        UUID otherNode = UUID.randomUUID();
        register(otherNode);

        Hold fault = server.holdNext();
        Future<?> faultSent = publishInBackground("FAULT");
        fault.awaitArrival();
        dispatcher.publish(report("OK"), otherNode, Duration.ofMillis(200));
        fault.release();
        faultSent.get();

        assertEquals(List.of("OK new", "FAULT new"), server.received);
    }

    @ParameterizedTest
    @MethodSource("failures")
    void reportAfterFailedPublishIsNew(Exception failure) throws Exception {
        publish("OK");

        server.deliverNextThenFail(failure);
        assertThrows(failure.getClass(), () -> publish("FAULT"));
        publish("OK");

        assertEquals(List.of("OK new", "FAULT new", "OK new"), server.received);
    }

    /** Failures after which the dispatcher cannot know whether the server got the report. */
    static Stream<Exception> failures() {
        return Stream.of(
                new TimeoutException(), new InterruptedException(), new IllegalStateException());
    }

    @Test
    void reportAfterGoodbyeIsNew() throws Exception {
        publish("OK");
        StatusReport goodbye =
                report("OK").toBuilder().setSystem(StatusReport.System.SYSTEM_GOODBYE).build();
        dispatcher.publish(goodbye, NODE_ID, TIMEOUT);
        publish("OK");

        assertEquals(List.of("OK new", "goodbye new", "OK new"), server.received);
    }

    @Test
    void reportAfterRegistrationIsNew() throws Exception {
        publish("OK");
        dispatcher.publish(Registration.getDefaultInstance(), NODE_ID, TIMEOUT);
        publish("OK");

        assertEquals(List.of("OK new", "registration", "OK new"), server.received);
    }

    @Test
    void reportAfterReconnectIsNew() throws Exception {
        publish("OK");
        server.reconnect();
        publish("OK");

        assertEquals(List.of("OK new", "OK new"), server.received);
    }

    /** Registers an offline node: its own lifecycle sends nothing, only the test publishes. */
    private void register(UUID nodeId) {
        INode node = mock(INode.class);
        when(node.getNodeId()).thenReturn(nodeId);
        dispatcher.register(node);
    }

    private static StatusReport report(String mode) {
        return StatusReport.newBuilder().setMode(mode).build();
    }

    private void publish(String mode) throws Exception {
        dispatcher.publish(report(mode), NODE_ID, TIMEOUT);
    }

    private Future<?> publishInBackground(String mode) {
        FutureTask<Void> task =
                new FutureTask<>(
                        () -> {
                            publish(mode);
                            return null;
                        });
        Thread.startVirtualThread(task);
        return task;
    }

    /** A report the server keeps back until the test releases it. */
    private record Hold(CountDownLatch arrived, CountDownLatch released) {
        void awaitArrival() throws InterruptedException {
            arrived.await();
        }

        void release() {
            released.countDown();
        }
    }

    /** The fusion node behind the client. Writes down each message it receives, like "OK new". */
    private static final class Server {
        final IClient client = mock(IClient.class);
        final List<String> received = new CopyOnWriteArrayList<>();
        private final Queue<Answer<Void>> upcoming = new ConcurrentLinkedQueue<>();
        private volatile BiConsumer<ConnectionState, Instant> stateListener;

        private final Answer<Void> receive =
                call -> {
                    received.add(describe(call.getArgument(0)));
                    return null;
                };

        Server() throws Exception {
            Answer<Void> publish =
                    call -> {
                        Answer<Void> scripted = upcoming.poll();
                        return scripted != null ? scripted.answer(call) : receive.answer(call);
                    };
            Answer<Void> listen =
                    call -> {
                        stateListener = call.getArgument(0);
                        return null;
                    };
            doAnswer(publish).when(client).publish(any(), any());
            doAnswer(listen).when(client).addStateChangeListener(any());
            when(client.connectionLossDetectionDelay()).thenReturn(Duration.ZERO);
        }

        /** Keeps the next report from reaching the server until the hold is released. */
        Hold holdNext() {
            Hold hold = new Hold(new CountDownLatch(1), new CountDownLatch(1));
            upcoming.add(
                    call -> {
                        hold.arrived().countDown();
                        hold.released().await();
                        return receive.answer(call);
                    });
            return hold;
        }

        /** Lets the next report reach the server, then fails its publish anyway. */
        void deliverNextThenFail(Exception failure) {
            upcoming.add(
                    call -> {
                        receive.answer(call);
                        throw failure;
                    });
        }

        /** Drops the connection and brings it back, the way the client reports it. */
        void reconnect() {
            stateListener.accept(ConnectionState.DISCONNECTED, Instant.now());
            stateListener.accept(ConnectionState.CONNECTED, Instant.now());
        }

        private static String describe(SapientMessage message) {
            if (message.hasRegistration()) {
                return "registration";
            }
            StatusReport report = message.getStatusReport();
            boolean goodbye = report.getSystem() == StatusReport.System.SYSTEM_GOODBYE;
            String info = report.getInfo().name().replace("INFO_", "").toLowerCase(Locale.ROOT);
            return (goodbye ? "goodbye" : report.getMode()) + " " + info;
        }
    }
}
