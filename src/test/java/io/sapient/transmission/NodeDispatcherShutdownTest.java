package io.sapient.transmission;

import static io.sapient.transmission.NodeDispatcherTest.AWAIT_MS;
import static io.sapient.transmission.NodeDispatcherTest.FUSION_NODE_ID;
import static io.sapient.transmission.NodeDispatcherTest.captureSubscription;
import static io.sapient.transmission.NodeDispatcherTest.mockNode;
import static io.sapient.transmission.NodeDispatcherTest.sendAck;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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

    /** How long a close() that does not wait for its nodes is given to run ahead. */
    private static final Duration CLOSE_HEADSTART = Duration.ofSeconds(1);

    private static NodeDispatcherConfig config() {
        return new NodeDispatcherConfig(
                Duration.ofMillis(10),
                Duration.ofSeconds(5),
                Duration.ofSeconds(5),
                Duration.ofMinutes(2),
                FUSION_NODE_ID,
                Duration.ZERO);
    }

    /** A mocked client that reports no detection delay. These tests drive the timing themselves. */
    static IClient mockClient() {
        IClient client = mock(IClient.class);
        when(client.connectionLossDetectionDelay()).thenReturn(Duration.ZERO);
        return client;
    }

    /** Waits for the latch, restoring the interrupt flag afterwards instead of bailing out. */
    private static void awaitIgnoringInterruption(CountDownLatch latch) {
        boolean interrupted = false;
        while (latch.getCount() > 0) {
            try {
                latch.await();
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

    /** A status report from the status loop, as opposed to the goodbye sent by {@code close()}. */
    private static SapientMessage isLiveStatusReport() {
        return argThat(
                (SapientMessage m) ->
                        m.getContentCase() == SapientMessage.ContentCase.STATUS_REPORT
                                && m.getStatusReport().getSystem()
                                        != StatusReport.System.SYSTEM_GOODBYE);
    }

    @Test
    @Timeout(20)
    void clientClosedExactlyOnceWhenNodesGoOfflineThenDispatcherCloses() throws Exception {
        UUID nodeId1 = UUID.randomUUID();
        UUID nodeId2 = UUID.randomUUID();
        AtomicBoolean online1 = new AtomicBoolean(true);
        AtomicBoolean online2 = new AtomicBoolean(true);
        IClient client = mockClient();
        NodeDispatcher dispatcher = new NodeDispatcher(client, config());
        Consumer<SapientMessage> onMessage = captureSubscription(client);

        dispatcher.register(mockNode(nodeId1, online1, 50));
        dispatcher.register(mockNode(nodeId2, online2, 50));

        verify(client, timeout(AWAIT_MS).atLeast(2)).publish(isRegistration(), any(Duration.class));
        sendAck(onMessage, nodeId1, true);
        sendAck(onMessage, nodeId2, true);
        verify(client, timeout(AWAIT_MS).atLeast(2)).publish(isStatusReport(), any(Duration.class));

        online1.set(false);
        online2.set(false);
        verify(client, timeout(AWAIT_MS)).close();

        dispatcher.close();
        Thread.sleep(200);

        verify(client, times(1)).close();
    }

    @Test
    @Timeout(20)
    void unregisterClosesClientWhenLastOnlineNodeRemoved() throws Exception {
        UUID nodeId = UUID.randomUUID();
        IClient client = mockClient();
        INode node = mockNode(nodeId, new AtomicBoolean(true), 50);
        NodeDispatcher dispatcher = new NodeDispatcher(client, config());
        Consumer<SapientMessage> onMessage = captureSubscription(client);

        dispatcher.register(node);
        verify(client, timeout(AWAIT_MS)).publish(isRegistration(), any(Duration.class));
        sendAck(onMessage, nodeId, true);
        verify(client, timeout(AWAIT_MS).atLeastOnce())
                .publish(isStatusReport(), any(Duration.class));

        dispatcher.unregister(node);

        verify(client, timeout(AWAIT_MS)).close();

        dispatcher.close();
        verify(client, times(1)).close();
    }

    @Test
    @Timeout(25)
    void nodeDoesNotPublishAfterClientClosed() throws Exception {
        UUID nodeId = UUID.randomUUID();
        IClient client = mockClient();
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

        // A node that stays inside its callback until this test lets it out, ignoring the
        // interruption but restoring the flag on the way out so the lifecycle thread still
        // terminates once the call returns. Only the first call blocks — holding up the
        // goodbye published by close() as well would hide the late status report behind it.
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean firstCall = new AtomicBoolean(true);
        Answer<StatusReport> blockingStatusReport =
                inv -> {
                    if (firstCall.compareAndSet(true, false)) {
                        entered.countDown();
                        awaitIgnoringInterruption(release);
                    }
                    return StatusReport.getDefaultInstance();
                };
        INode node = mockNode(nodeId, new AtomicBoolean(true), 50);
        when(node.getStatusReport()).thenAnswer(blockingStatusReport);

        NodeDispatcher dispatcher = new NodeDispatcher(client, config());
        Consumer<SapientMessage> onMessage = captureSubscription(client);
        dispatcher.register(node);

        verify(client, timeout(AWAIT_MS)).publish(isRegistration(), any(Duration.class));
        sendAck(onMessage, nodeId, true);
        assertTrue(
                entered.await(AWAIT_MS, TimeUnit.MILLISECONDS),
                "the node lifecycle thread should be inside the callback");

        // close() blocks until the node is done, so it cannot run on this thread
        Thread closer = Thread.startVirtualThread(dispatcher::close);
        // give a close() that does not wait for the node every chance to finish first
        Thread.sleep(CLOSE_HEADSTART.toMillis());
        release.countDown();

        // the released node publishes the status report it was holding — wait for it, then
        // for the close to finish, so the check below cannot run before either has happened
        verify(client, timeout(AWAIT_MS)).publish(isLiveStatusReport(), any(Duration.class));
        closer.join(AWAIT_MS);

        assertFalse(
                publishedAfterClose.get(), "no message may reach the client after it was closed");
    }
}
