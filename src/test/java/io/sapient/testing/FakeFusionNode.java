package io.sapient.testing;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.sapient.transmission.NodeDispatcher;
import io.sapient.transmission.NodeDispatcherConfig;
import io.sapient.transport.ConnectionState;
import io.sapient.transport.IClient;
import io.sapient.transport.ISocketProvider;
import io.sapient.transport.health.HealthCheckType;
import io.sapient.transport.health.KeepalivePrefixes;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import lombok.SneakyThrows;
import org.mockito.stubbing.Answer;
import uk.gov.dstl.sapientmsg.bsiflex335v2.AlertAck;
import uk.gov.dstl.sapientmsg.bsiflex335v2.Error;
import uk.gov.dstl.sapientmsg.bsiflex335v2.RegistrationAck;
import uk.gov.dstl.sapientmsg.bsiflex335v2.SapientMessage;
import uk.gov.dstl.sapientmsg.bsiflex335v2.StatusReport;
import uk.gov.dstl.sapientmsg.bsiflex335v2.Task;

/**
 * The fusion node a test talks to. Unless the builder says otherwise it accepts every registration.
 *
 * <p>Every message it receives becomes a line such as {@code "n1 status OK new"}, read with {@link
 * #expect} and {@link #await}. A test reaches it through one of two attachments:
 *
 * <ul>
 *   <li>{@link #client()}, an in-memory {@code IClient} for a {@code NodeDispatcher}. Replies and
 *       connection events reach the dispatcher on a thread of its own, in order, the way a socket's
 *       read loop delivers them. A verb such as {@link #sendTask} returns once the dispatcher has
 *       handled the message; an automatic answer to a registration does not wait.
 *   <li>{@link #sockets()}, in-memory sockets with real framing for a {@code SocketClient}. They
 *       hold a real port for probes, so close the fusion node when the test is done.
 * </ul>
 */
public final class FakeFusionNode implements AutoCloseable {

    /** The fusion node's id, the destination of every message the test config sends. */
    public static final UUID ID = TestConfig.FUSION_NODE_ID;

    /** What happens to one published message instead of a plain delivery. */
    @FunctionalInterface
    private interface Step {
        void apply(SapientMessage message) throws Exception;
    }

    private final boolean rejectingAll;
    private final Set<String> rejected;
    private final boolean manualAnswers;
    private final HealthCheckType keepalive;

    private final Conversation<SapientMessage> received = new Conversation<>();
    private final Map<String, String> names = new ConcurrentHashMap<>();
    private final Queue<Step> steps = new ConcurrentLinkedQueue<>();
    private final AtomicReference<Exception> everyStatusReportFails = new AtomicReference<>();
    private final AtomicReference<Consumer<SapientMessage>> subscriber =
            new AtomicReference<>(message -> {});
    private final List<BiConsumer<ConnectionState, Instant>> listeners =
            new CopyOnWriteArrayList<>();
    private final AtomicReference<ConnectionState> state =
            new AtomicReference<>(ConnectionState.DISCONNECTED);
    private final Count connects = new Count("connects");
    private final Count disconnects = new Count("disconnects");
    private final ExecutorService delivery =
            Executors.newSingleThreadExecutor(
                    Thread.ofVirtual().name("fake-fusion-node").factory());
    private final IClient client = mock(IClient.class);
    private volatile FakeSockets sockets;

    /** A fusion node that accepts every registration. */
    public FakeFusionNode() {
        this(builder());
    }

    private FakeFusionNode(Builder builder) {
        rejectingAll = builder.rejectingAll;
        rejected = Set.copyOf(builder.rejected);
        manualAnswers = builder.manualAnswers;
        keepalive = builder.keepalive;
        names.putAll(builder.names);
        stubClient();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The client a {@link NodeDispatcher} talks to this fusion node through. */
    public IClient client() {
        return client;
    }

    /**
     * Sockets for a {@code SocketClient}, carrying real SAPIENT frames. From the first call on, the
     * fusion node sends through them. Host and port name a real listener on the loopback address,
     * so probes and the NETCAT check reach it. The keepalive set with the builder is answered in
     * band.
     */
    public synchronized ISocketProvider sockets() {
        if (sockets == null) {
            sockets =
                    new FakeSockets(
                            this::receive,
                            this::receiveUnreadable,
                            KeepalivePrefixes.of(keepalive),
                            connects,
                            disconnects);
        }
        return sockets;
    }

    /** A dispatcher on {@link #client()} with {@link TestConfig#defaults()}, nodes registered. */
    public NodeDispatcher connect(FakeEdgeNode... nodes) {
        return connect(TestConfig.defaults(), nodes);
    }

    /** A dispatcher on {@link #client()} with its own config, nodes registered. */
    public NodeDispatcher connect(NodeDispatcherConfig config, FakeEdgeNode... nodes) {
        NodeDispatcher dispatcher = new NodeDispatcher(client, config);
        register(dispatcher, nodes);
        return dispatcher;
    }

    /** Registers more nodes with a dispatcher {@link #connect} built. */
    public void register(NodeDispatcher dispatcher, FakeEdgeNode... nodes) {
        for (FakeEdgeNode node : nodes) {
            remember(node);
            node.attach(dispatcher);
            dispatcher.register(node.asNode());
        }
    }

    // --- reading what arrived

    public void expect(String... lines) {
        received.expect(lines);
    }

    /**
     * Skips lines until it reads {@code line}, and returns its message. Skipped lines are gone,
     * other nodes' lines too: with several nodes, wait on each node's own callbacks instead.
     */
    public SapientMessage await(String line) {
        return received.await(line);
    }

    public SapientMessage next() {
        return received.next();
    }

    public void expectNothingMore() {
        received.expectNothingMore();
    }

    public List<String> lines() {
        return received.lines();
    }

    // --- answering

    public void acceptRegistration(FakeEdgeNode node) {
        send(ack(remember(node), true));
    }

    public void rejectRegistration(FakeEdgeNode node) {
        send(ack(remember(node), false));
    }

    /** A {@code Task} with the request "registration", which asks the node to register again. */
    public void requestRegistration(FakeEdgeNode node) {
        sendTask(
                node,
                task -> task.setCommand(Task.Command.newBuilder().setRequest("registration")));
    }

    public void sendTask(FakeEdgeNode node, Consumer<Task.Builder> edit) {
        send(to(remember(node)).setTask(edited(Task.newBuilder(), edit)).build());
    }

    public void sendError(FakeEdgeNode node, Consumer<Error.Builder> edit) {
        send(to(remember(node)).setError(edited(Error.newBuilder(), edit)).build());
    }

    public void sendAlertAck(FakeEdgeNode node, Consumer<AlertAck.Builder> edit) {
        send(to(remember(node)).setAlertAck(edited(AlertAck.newBuilder(), edit)).build());
    }

    /**
     * Delivers any message, also to a node without a fake. Through {@link #client()} it returns
     * once the dispatcher has handled the message; through {@link #sockets()}, once the frame is on
     * the current connection.
     */
    public void send(SapientMessage message) {
        FakeSockets attached = sockets;
        if (attached != null) {
            attached.send(message);
        } else {
            deliverNow(() -> subscriber.get().accept(message));
        }
    }

    // --- faults of the client attachment; the sockets have their own below

    /** Stops the next published message before it arrives, until the test releases it. */
    public Held holdNext() {
        Held held = new Held();
        steps.add(
                message -> {
                    held.enter();
                    receive(message);
                });
        return held;
    }

    /** The next publish throws {@code failure}, and its message never arrives. */
    public void failNext(Exception failure) {
        steps.add(
                message -> {
                    throw failure;
                });
    }

    /** The next message arrives, and then its publish throws {@code failure}. */
    public void deliverNextThenFail(Exception failure) {
        steps.add(
                message -> {
                    receive(message);
                    throw failure;
                });
    }

    /**
     * From now on every status report throws {@code failure} and never arrives; other messages
     * still do. The server has stopped confirming the node's reports.
     */
    public void failEveryStatusReport(Exception failure) {
        everyStatusReportFails.set(failure);
    }

    // --- the connection

    /**
     * Reports DISCONNECTED now and CONNECTED {@code gap} later, by their timestamps, to the
     * listeners of {@link #client()}.
     */
    public void outage(Duration gap) {
        Instant lost = Instant.now();
        deliverNow(
                () -> {
                    report(ConnectionState.DISCONNECTED, lost);
                    report(ConnectionState.CONNECTED, lost.plus(gap));
                });
    }

    /** Reports one connection event to the listeners of {@link #client()}. */
    public void reportConnectionState(ConnectionState state, Instant at) {
        deliverNow(() -> report(state, at));
    }

    /** Times the client connected: {@code start()} on the client, or a socket handed out. */
    public int connects() {
        return connects.get();
    }

    /** Times the client disconnected: {@code close()} on the client, or on a socket. */
    public int disconnects() {
        return disconnects.get();
    }

    public void awaitConnects(int count) {
        connects.await(count);
    }

    public void awaitDisconnects(int count) {
        disconnects.await(count);
    }

    // --- faults of the socket attachment

    /** Sends a bare 4-byte length prefix on the current connection, such as a broken frame. */
    public void sendRawPrefix(int prefix) {
        attachedSockets().sendRawPrefix(prefix);
    }

    /**
     * Ends the current connection from the fusion node's side, so the client reads EOF. What the
     * client still writes on it arrives, as after a TCP half-close, so a write that should not
     * happen shows up as a line.
     */
    public void drop() {
        attachedSockets().drop();
    }

    /**
     * The fusion node stops reading. The next write stops halfway through its frame, until the test
     * releases it; closing the socket fails it, and an interrupt closes the socket first.
     */
    public Held stopReading() {
        return attachedSockets().stopReading();
    }

    /** The client's next connect is refused; the one after it succeeds. */
    public void refuseNextConnection() {
        attachedSockets().refuseNextConnection();
    }

    /** Probes, the NETCAT check and connects are refused from now on. */
    public void goUnreachable() {
        attachedSockets().goUnreachable();
    }

    /** Probes and connects succeed again. */
    public void goReachable() {
        attachedSockets().goReachable();
    }

    /** Releases the port the sockets hold. Does nothing when the test used only the client. */
    @Override
    public void close() {
        FakeSockets attached = sockets;
        if (attached != null) {
            attached.close();
        }
    }

    // --- inside

    @SneakyThrows
    private void stubClient() {
        Answer<Void> publish =
                call -> {
                    onPublish(call.getArgument(0));
                    return null;
                };
        Answer<Void> subscribe =
                call -> {
                    subscriber.set(call.getArgument(0));
                    return null;
                };
        Answer<Void> listen =
                call -> {
                    listeners.add(call.getArgument(0));
                    return null;
                };
        Answer<Void> forget =
                call -> {
                    listeners.remove(call.getArgument(0));
                    return null;
                };
        Answer<Void> start =
                call -> {
                    deliverLater(
                            () -> reportNow(ConnectionState.CONNECTING, ConnectionState.CONNECTED));
                    connects.increment();
                    return null;
                };
        Answer<Void> close =
                call -> {
                    deliverLater(
                            () -> reportNow(ConnectionState.DISCONNECTED, ConnectionState.CLOSED));
                    disconnects.increment();
                    return null;
                };
        doAnswer(publish).when(client).publish(any(), any());
        doAnswer(subscribe).when(client).subscribe(any());
        doAnswer(listen).when(client).addStateChangeListener(any());
        doAnswer(forget).when(client).removeStateChangeListener(any());
        doAnswer(start).when(client).start();
        doAnswer(close).when(client).close();
        when(client.getState()).thenAnswer(call -> state.get());
        when(client.probeReachable(any())).thenReturn(true);
        when(client.connectionLossDetectionDelay()).thenReturn(Duration.ZERO);
    }

    private void onPublish(SapientMessage message) throws Exception {
        Exception failure = everyStatusReportFails.get();
        if (failure != null && message.hasStatusReport()) {
            throw failure;
        }
        Step step = steps.poll();
        if (step == null) {
            receive(message);
        } else {
            step.apply(message);
        }
    }

    private void receiveUnreadable() {
        received.add("unreadable frame", SapientMessage.getDefaultInstance());
    }

    /** A message published through the client; its answer comes on the delivery thread. */
    private void receive(SapientMessage message) {
        receive(message, ack -> deliverLater(() -> subscriber.get().accept(ack)));
    }

    /**
     * Answers first, so whatever a test sends after it has read the line comes after the answer.
     */
    private void receive(SapientMessage message, Consumer<SapientMessage> reply) {
        if (message.hasRegistration() && !manualAnswers) {
            String node = message.getNodeId();
            boolean accepted = !rejectingAll && !rejected.contains(node);
            reply.accept(ack(node, accepted));
        }
        received.add(describe(message), message);
    }

    private String describe(SapientMessage message) {
        String node = names.getOrDefault(message.getNodeId(), shortId(message.getNodeId()));
        String what =
                switch (message.getContentCase()) {
                    case REGISTRATION -> "registration";
                    case STATUS_REPORT -> describe(message.getStatusReport());
                    case ALERT -> "alert";
                    case DETECTION_REPORT -> "detection";
                    case TASK_ACK ->
                            "task-ack "
                                    + lower(
                                            message.getTaskAck().getTaskStatus().name(),
                                            "TASK_STATUS_");
                    case REGISTRATION_ACK, TASK, ALERT_ACK, ERROR ->
                            lower(message.getContentCase().name(), "");
                    case CONTENT_NOT_SET -> "empty";
                };
        return node + " " + what;
    }

    private static String describe(StatusReport report) {
        String info = lower(report.getInfo().name(), "INFO_");
        if (report.getSystem() == StatusReport.System.SYSTEM_GOODBYE) {
            return "goodbye " + info;
        }
        return "status " + report.getMode() + " " + info;
    }

    /** {@code lower("INFO_NEW", "INFO_")} is {@code "new"}. */
    private static String lower(String name, String prefix) {
        String bare = name.startsWith(prefix) ? name.substring(prefix.length()) : name;
        return bare.toLowerCase(Locale.ROOT);
    }

    /** The first 8 characters of a node id, or {@code ?} for a message without one. */
    private static String shortId(String id) {
        if (id.isEmpty()) {
            return "?";
        }
        return id.length() > 8 ? id.substring(0, 8) : id;
    }

    private String remember(FakeEdgeNode node) {
        String id = node.id().toString();
        names.put(id, node.name());
        return id;
    }

    private static SapientMessage.Builder to(String node) {
        return SapientMessage.newBuilder().setNodeId(ID.toString()).setDestinationId(node);
    }

    private static SapientMessage ack(String node, boolean accepted) {
        return to(node).setRegistrationAck(RegistrationAck.newBuilder().setAcceptance(accepted))
                .build();
    }

    private void report(ConnectionState next, Instant at) {
        state.set(next);
        listeners.forEach(listener -> listener.accept(next, at));
    }

    private void reportNow(ConnectionState... sequence) {
        for (ConnectionState next : sequence) {
            report(next, Instant.now());
        }
    }

    private FakeSockets attachedSockets() {
        FakeSockets attached = sockets;
        if (attached == null) {
            throw new IllegalStateException("socket verbs need sockets() first");
        }
        return attached;
    }

    private void deliverLater(Runnable task) {
        delivery.execute(task);
    }

    private void deliverNow(Runnable task) {
        try {
            delivery.submit(task).get();
        } catch (ExecutionException e) {
            throw new AssertionError("the dispatcher failed to handle a message", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("still waiting for the dispatcher to handle a message", e);
        }
    }

    private static <B> B edited(B builder, Consumer<B> edit) {
        edit.accept(builder);
        return builder;
    }

    public static final class Builder {
        private boolean rejectingAll;
        private final Set<String> rejected = new HashSet<>();
        private final Map<String, String> names = new ConcurrentHashMap<>();
        private boolean manualAnswers;
        private HealthCheckType keepalive = HealthCheckType.NETCAT;

        private Builder() {}

        /** Answers every registration with acceptance=false. */
        public Builder withRejectingAllRegistrations() {
            rejectingAll = true;
            return this;
        }

        /** Answers registrations from these nodes with acceptance=false, and accepts the rest. */
        public Builder withRejectingRegistrationsFrom(FakeEdgeNode... nodes) {
            for (FakeEdgeNode node : nodes) {
                rejected.add(node.id().toString());
                names.put(node.id().toString(), node.name());
            }
            return this;
        }

        /** Answers registrations from nodes with these ids with acceptance=false. */
        public Builder withRejectingRegistrationsFrom(UUID... ids) {
            for (UUID id : ids) {
                rejected.add(id.toString());
            }
            return this;
        }

        /** Answers no registration by itself; the test answers with acceptRegistration. */
        public Builder withManualRegistrationAnswers() {
            manualAnswers = true;
            return this;
        }

        /** The keepalive to answer on the socket attachment; the default is NETCAT, nothing. */
        public Builder withKeepalive(HealthCheckType type) {
            KeepalivePrefixes.of(type); // rejects a type that has no wire format at all
            keepalive = type;
            return this;
        }

        public FakeFusionNode build() {
            return new FakeFusionNode(this);
        }
    }
}
