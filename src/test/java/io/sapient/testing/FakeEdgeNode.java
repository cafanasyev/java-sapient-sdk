package io.sapient.testing;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.protobuf.Message;
import io.sapient.transmission.INode;
import io.sapient.transmission.INodeDispatcher;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import org.mockito.stubbing.Answer;
import uk.gov.dstl.sapientmsg.bsiflex335v2.Alert;
import uk.gov.dstl.sapientmsg.bsiflex335v2.AlertAck;
import uk.gov.dstl.sapientmsg.bsiflex335v2.DetectionReport;
import uk.gov.dstl.sapientmsg.bsiflex335v2.Error;
import uk.gov.dstl.sapientmsg.bsiflex335v2.Registration;
import uk.gov.dstl.sapientmsg.bsiflex335v2.RegistrationAck;
import uk.gov.dstl.sapientmsg.bsiflex335v2.SapientMessage;
import uk.gov.dstl.sapientmsg.bsiflex335v2.StatusReport;
import uk.gov.dstl.sapientmsg.bsiflex335v2.Task;

/**
 * An edge node the test controls. Unless the builder says otherwise it is online, reports {@code
 * SYSTEM_OK} with mode {@code OK}, and registers with a 50 ms status interval.
 *
 * <p>The callbacks it gets from the dispatcher become lines: {@code "ack accepted"}, {@code "ack
 * rejected"}, {@code "task <taskId>"}, {@code "error <first message>"} and {@code "alert-ack
 * <alertId>"}. Once {@link FakeFusionNode#connect} has registered it, it also sends messages
 * through the dispatcher, the way an application does.
 */
public final class FakeEdgeNode {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final String name;
    private final UUID id = UUID.randomUUID();
    private final AtomicBoolean online;
    private final Registration registration;
    private final AtomicReference<StatusReport> statusReport;
    private final Conversation<Message> callbacks = new Conversation<>();
    private final Map<NodeCall, Held> holds = new ConcurrentHashMap<>();
    private final INode node = mock(INode.class);
    private volatile INodeDispatcher dispatcher;

    /** A node with every default. */
    public FakeEdgeNode(String name) {
        this(builder(name));
    }

    private FakeEdgeNode(Builder builder) {
        name = builder.name;
        online = new AtomicBoolean(builder.online);
        Registration.Builder registration =
                withInterval(Registration.newBuilder(), Duration.ofMillis(50));
        builder.registrationEdits.forEach(edit -> edit.accept(registration));
        this.registration = registration.build();
        StatusReport.Builder status =
                StatusReport.newBuilder().setSystem(StatusReport.System.SYSTEM_OK).setMode("OK");
        builder.statusEdits.forEach(edit -> edit.accept(status));
        statusReport = new AtomicReference<>(status.build());
        stubNode();
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    /** The {@link INode} the dispatcher talks to. */
    public INode asNode() {
        return node;
    }

    public void goOffline() {
        online.set(false);
    }

    public void goOnline() {
        online.set(true);
    }

    /** Changes the status report the node's lifecycle sends from now on. */
    public void updateStatusReport(Consumer<StatusReport.Builder> edit) {
        statusReport.updateAndGet(report -> edited(report.toBuilder(), edit).build());
    }

    /** Sends the current status report now, outside the lifecycle. */
    public SapientMessage sendStatusReport() throws TimeoutException, InterruptedException {
        return dispatcher().publish(statusReport.get(), id, TIMEOUT);
    }

    /** Sends the current status report with an edit, once; the current report stays as it is. */
    public SapientMessage sendStatusReport(Consumer<StatusReport.Builder> edit)
            throws TimeoutException, InterruptedException {
        StatusReport report = edited(statusReport.get().toBuilder(), edit).build();
        return dispatcher().publish(report, id, TIMEOUT);
    }

    public SapientMessage sendRegistration() throws TimeoutException, InterruptedException {
        return dispatcher().publish(registration, id, TIMEOUT);
    }

    public SapientMessage sendAlert(Consumer<Alert.Builder> edit)
            throws TimeoutException, InterruptedException {
        return dispatcher().publish(edited(Alert.newBuilder(), edit).build(), id, TIMEOUT);
    }

    public SapientMessage sendDetection(Consumer<DetectionReport.Builder> edit)
            throws TimeoutException, InterruptedException {
        DetectionReport detection = edited(DetectionReport.newBuilder(), edit).build();
        return dispatcher().publish(detection, id, TIMEOUT);
    }

    /** Stops the next {@code call} of this node until the test releases it. */
    public Held holdNext(NodeCall call) {
        Held held = new Held();
        holds.put(call, held);
        return held;
    }

    public void expect(String... lines) {
        callbacks.expect(lines);
    }

    public void await(String line) {
        callbacks.await(line);
    }

    public void expectNothingMore() {
        callbacks.expectNothingMore();
    }

    @Override
    public String toString() {
        return name;
    }

    void attach(INodeDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    private INodeDispatcher dispatcher() {
        INodeDispatcher attached = dispatcher;
        if (attached == null) {
            throw new IllegalStateException(
                    name + " is not connected; register it with FakeFusionNode.connect");
        }
        return attached;
    }

    private void stubNode() {
        when(node.getNodeId()).thenReturn(id);
        when(node.getRegistration()).thenReturn(registration);
        when(node.isOnline()).thenAnswer(call -> answer(NodeCall.ONLINE_CHECK));
        when(node.getStatusReport()).thenAnswer(call -> answer(NodeCall.STATUS_REPORT));
        doAnswer(
                        line(
                                RegistrationAck.class,
                                ack -> ack.getAcceptance() ? "ack accepted" : "ack rejected"))
                .when(node)
                .onRegistrationAck(any());
        doAnswer(line(Task.class, task -> "task " + task.getTaskId())).when(node).onTask(any());
        doAnswer(line(Error.class, error -> "error " + firstMessage(error)))
                .when(node)
                .onError(any());
        doAnswer(line(AlertAck.class, ack -> "alert-ack " + ack.getAlertId()))
                .when(node)
                .onAlertAck(any());
    }

    private Object answer(NodeCall call) {
        Held held = holds.remove(call);
        if (held != null) {
            held.enter();
        }
        return switch (call) {
            case ONLINE_CHECK -> online.get();
            case STATUS_REPORT -> statusReport.get();
        };
    }

    /** An answer that writes the callback's argument down as a line. */
    private <M extends Message> Answer<Void> line(Class<M> type, Function<M, String> describe) {
        return call -> {
            M message = type.cast(call.getArgument(0));
            callbacks.add(describe.apply(message), message);
            return null;
        };
    }

    private static String firstMessage(Error error) {
        return error.getErrorMessageCount() == 0 ? "" : error.getErrorMessage(0);
    }

    private static <B> B edited(B builder, Consumer<B> edit) {
        edit.accept(builder);
        return builder;
    }

    private static Registration.Builder withInterval(
            Registration.Builder registration, Duration interval) {
        registration
                .getStatusDefinitionBuilder()
                .getStatusIntervalBuilder()
                .setUnits(Registration.TimeUnits.TIME_UNITS_MILLISECONDS)
                .setValue((float) interval.toMillis());
        return registration;
    }

    public static final class Builder {
        private final String name;
        private boolean online = true;
        private final List<Consumer<StatusReport.Builder>> statusEdits = new ArrayList<>();
        private final List<Consumer<Registration.Builder>> registrationEdits = new ArrayList<>();

        private Builder(String name) {
            this.name = name;
        }

        /** Starts offline, so the node's own lifecycle sends nothing until it goes online. */
        public Builder withOfflineStart() {
            online = false;
            return this;
        }

        /** Edits the status report the node starts with. Several edits apply in order. */
        public Builder withStatusReport(Consumer<StatusReport.Builder> edit) {
            statusEdits.add(edit);
            return this;
        }

        /** Edits the registration. Several edits apply in order. */
        public Builder withRegistration(Consumer<Registration.Builder> edit) {
            registrationEdits.add(edit);
            return this;
        }

        /** Sets the registration's status interval, which SAPIENT stores as a value and units. */
        public Builder withStatusInterval(Duration interval) {
            return withRegistration(registration -> withInterval(registration, interval));
        }

        public FakeEdgeNode build() {
            return new FakeEdgeNode(this);
        }
    }
}
