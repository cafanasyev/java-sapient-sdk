package io.sapient.transmission;

import static io.sapient.transmission.NodeDispatcherTest.FUSION_NODE_ID;
import static io.sapient.transmission.NodeDispatcherTest.captureSubscription;
import static io.sapient.transmission.NodeDispatcherTest.mockNode;
import static io.sapient.transmission.NodeDispatcherTest.sendAck;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.sapient.transport.IClient;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.stubbing.Answer;
import uk.gov.dstl.sapientmsg.bsiflex335v2.SapientMessage;
import uk.gov.dstl.sapientmsg.bsiflex335v2.StatusReport;

/** Shutdown behaviour of {@link NodeDispatcher} with several nodes registered. */
@Execution(ExecutionMode.CONCURRENT)
class NodeDispatcherShutdownTest {

    /** How long a node stays inside a callback after being asked to stop. */
    private static final Duration SLOW_CALL = Duration.ofMillis(300);

    private static NodeDispatcherConfig config() {
        return new NodeDispatcherConfig(
                Duration.ofMillis(10),
                Duration.ofSeconds(5),
                Duration.ofSeconds(5),
                Duration.ofMinutes(2),
                Duration.ZERO,
                FUSION_NODE_ID,
                Duration.ZERO);
    }

    private static void sleepIgnoringInterruption(Duration duration) {
        long deadline = System.nanoTime() + duration.toNanos();
        boolean interrupted = false;
        while (System.nanoTime() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static SapientMessage isRegistration() {
        return argThat(
                (SapientMessage m) ->
                        m.getContentCase() == SapientMessage.ContentCase.REGISTRATION);
    }

    private static SapientMessage isStatusReport() {
        return argThat(
                (SapientMessage m) ->
                        m.getContentCase() == SapientMessage.ContentCase.STATUS_REPORT);
    }

    @Test
    @Timeout(10)
    void clientClosedExactlyOnceWhenNodesGoOfflineThenDispatcherCloses() throws Exception {
        UUID nodeId1 = UUID.randomUUID();
        UUID nodeId2 = UUID.randomUUID();
        AtomicBoolean online1 = new AtomicBoolean(true);
        AtomicBoolean online2 = new AtomicBoolean(true);
        IClient client = mock(IClient.class);
        NodeDispatcher dispatcher = new NodeDispatcher(client, config());
        Consumer<SapientMessage> onMessage = captureSubscription(client);

        dispatcher.register(mockNode(nodeId1, online1, 50));
        dispatcher.register(mockNode(nodeId2, online2, 50));

        verify(client, timeout(2000).atLeast(2)).publish(isRegistration(), any(Duration.class));
        sendAck(onMessage, nodeId1, true);
        sendAck(onMessage, nodeId2, true);
        verify(client, timeout(2000).atLeast(2)).publish(isStatusReport(), any(Duration.class));

        online1.set(false);
        online2.set(false);
        verify(client, timeout(2000)).close();

        dispatcher.close();
        Thread.sleep(200);

        verify(client, times(1)).close();
    }

    @Test
    @Timeout(10)
    void unregisterClosesClientWhenLastOnlineNodeRemoved() throws Exception {
        UUID nodeId = UUID.randomUUID();
        IClient client = mock(IClient.class);
        INode node = mockNode(nodeId, new AtomicBoolean(true), 50);
        NodeDispatcher dispatcher = new NodeDispatcher(client, config());
        Consumer<SapientMessage> onMessage = captureSubscription(client);

        dispatcher.register(node);
        verify(client, timeout(2000)).publish(isRegistration(), any(Duration.class));
        sendAck(onMessage, nodeId, true);
        verify(client, timeout(2000).atLeastOnce()).publish(isStatusReport(), any(Duration.class));

        dispatcher.unregister(node);

        verify(client, timeout(2000)).close();

        dispatcher.close();
        verify(client, times(1)).close();
    }

    @Test
    @Timeout(15)
    void nodeDoesNotPublishAfterClientClosed() throws Exception {
        UUID nodeId = UUID.randomUUID();
        IClient client = mock(IClient.class);
        AtomicBoolean clientClosed = new AtomicBoolean(false);
        AtomicBoolean publishedAfterClose = new AtomicBoolean(false);

        Answer<Void> closeAnswer =
                inv -> {
                    clientClosed.set(true);
                    return null;
                };
        doAnswer(closeAnswer).when(client).close();

        Answer<Void> publishAnswer =
                inv -> {
                    if (clientClosed.get()) {
                        publishedAfterClose.set(true);
                    }
                    return null;
                };
        doAnswer(publishAnswer)
                .when(client)
                .publish(any(SapientMessage.class), any(Duration.class));

        // A node that keeps running for a while after being asked to stop: it swallows the
        // interruption but restores the flag on the way out, so the lifecycle thread still
        // terminates once the call returns. Only the first call is slow — delaying the
        // goodbye published by close() as well would hide the late status report behind it.
        AtomicBoolean firstCall = new AtomicBoolean(true);
        Answer<StatusReport> slowStatusReport =
                inv -> {
                    if (firstCall.compareAndSet(true, false)) {
                        sleepIgnoringInterruption(SLOW_CALL);
                    }
                    return StatusReport.getDefaultInstance();
                };
        INode node = mockNode(nodeId, new AtomicBoolean(true), 50);
        when(node.getStatusReport()).thenAnswer(slowStatusReport);

        NodeDispatcher dispatcher = new NodeDispatcher(client, config());
        Consumer<SapientMessage> onMessage = captureSubscription(client);
        dispatcher.register(node);

        verify(client, timeout(2000)).publish(isRegistration(), any(Duration.class));
        sendAck(onMessage, nodeId, true);
        // the node lifecycle thread is now inside the slow callback
        verify(node, timeout(2000)).getStatusReport();

        dispatcher.close();
        Thread.sleep(SLOW_CALL.toMillis() * 3);

        assertFalse(
                publishedAfterClose.get(), "no message may reach the client after it was closed");
    }
}
