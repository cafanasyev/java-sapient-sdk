package io.sapient.transmission;

import com.google.protobuf.Timestamp;
import io.sapient.transport.IClient;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;
import lombok.NonNull;
import uk.gov.dstl.sapientmsg.bsiflex335v2.Alert;
import uk.gov.dstl.sapientmsg.bsiflex335v2.DetectionReport;
import uk.gov.dstl.sapientmsg.bsiflex335v2.Registration;
import uk.gov.dstl.sapientmsg.bsiflex335v2.SapientMessage;
import uk.gov.dstl.sapientmsg.bsiflex335v2.StatusReport;

/**
 * NodeDispatcher connects to a fusion node, registers all provided nodes and sends StatusReports
 * based on time interval specified by each edge/fusion node in its Registration message.
 *
 * <p>Each registered node is managed by its own virtual thread that handles the full lifecycle:
 * online polling → registration → wait for ack → status reporting → goodbye on offline. Publish of
 * SapientMessages can also be initiated externally (not by the node thread).
 */
@Singleton
public class NodeDispatcher implements INodeDispatcher {

    private static final Logger logger = Logger.getLogger(NodeDispatcher.class.getName());

    @NonNull private final IClient client;
    @NonNull private final NodeDispatcherConfig config;

    final ConcurrentMap<UUID, NodeWrapper> nodes = new ConcurrentHashMap<>();

    /**
     * Creates a dispatcher backed by the given client and configuration.
     *
     * @param client the transport client used to send and receive messages
     * @param config dispatcher configuration (polling intervals, timeouts)
     */
    @Inject
    public NodeDispatcher(@NonNull IClient client, @NonNull NodeDispatcherConfig config) {
        this.client = client;
        this.config = config;
        client.subscribe(this::onMessage);
    }

    private void onMessage(ByteBuffer buffer) {
        try {
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            SapientMessage message = SapientMessage.parseFrom(bytes);
            UUID destinationId = UUID.fromString(message.getDestinationId());
            logger.log(
                    Level.INFO,
                    "received {0} for node: {1}",
                    new Object[] {message.getContentCase(), destinationId});
            NodeWrapper node = nodes.get(destinationId);
            if (node == null) {
                logger.log(Level.SEVERE, "no node registered for destination: {0}", destinationId);
                return;
            }

            switch (message.getContentCase()) {
                case REGISTRATION_ACK -> {
                    if (message.getRegistrationAck().getAcceptance()) {
                        node.fusionNodeId.set(UUID.fromString(message.getNodeId()));
                    }
                    if (!node.ackQueue.offer(message.getRegistrationAck())) {
                        logger.log(
                                Level.SEVERE,
                                "ack queue full, dropping ack for node: {0}",
                                destinationId);
                    }
                }
                case ALERT_ACK -> node.node.onAlertAck(message.getAlertAck());
                case TASK -> node.node.onTask(message.getTask());
                default -> {}
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "failed to process incoming message", e);
        }
    }

    @Override
    public void register(INode node) {
        logger.log(Level.INFO, "registering the node: " + node.getNodeId());
        nodes.computeIfAbsent(node.getNodeId(), k -> new NodeWrapper(node, this, config));
    }

    @Override
    public void unregister(INode node) {
        logger.log(Level.INFO, "unregistering the node: " + node.getNodeId());

        NodeWrapper wrapper = nodes.remove(node.getNodeId());
        if (wrapper == null) return;

        wrapper.close();
        if (!wrapper.registered.getAndSet(false)) return;

        try {
            logger.log(Level.INFO, "sending goodbye for the node: " + node.getNodeId());
            goodbye(node.getNodeId(), config.publishTimeout());
        } catch (TimeoutException | InterruptedException e) {
            logger.log(Level.SEVERE, "failed to send goodbye for the node: " + node.getNodeId(), e);
        }
    }

    @Override
    public void publish(Registration registration, UUID nodeId, Duration timeout)
            throws TimeoutException, InterruptedException {
        publish(SapientMessage.newBuilder().setRegistration(registration), nodeId, timeout);
    }

    @Override
    public void publish(StatusReport status, UUID nodeId, Duration timeout)
            throws TimeoutException, InterruptedException {
        NodeWrapper node = nodes.get(nodeId);
        StatusReport.Info info = StatusReport.Info.INFO_NEW;
        if (node != null) {
            StatusReport prev = node.lastStatusReport.getAndSet(status);
            if (prev != null && clearInfo(prev).equals(clearInfo(status))) {
                info = StatusReport.Info.INFO_UNCHANGED;
            }
        }
        StatusReport withInfo = status.toBuilder().setInfo(info).build();
        publish(SapientMessage.newBuilder().setStatusReport(withInfo), nodeId, timeout);
    }

    private static StatusReport clearInfo(StatusReport status) {
        return status.toBuilder().clearInfo().build();
    }

    @Override
    public void publish(Alert alert, UUID nodeId, Duration timeout)
            throws TimeoutException, InterruptedException {
        publish(SapientMessage.newBuilder().setAlert(alert), nodeId, timeout);
    }

    @Override
    public void publish(DetectionReport detection, UUID nodeId, Duration timeout)
            throws TimeoutException, InterruptedException {
        publish(SapientMessage.newBuilder().setDetectionReport(detection), nodeId, timeout);
    }

    @Override
    public void goodbye(UUID nodeId, Duration timeout)
            throws TimeoutException, InterruptedException {
        publish(SapientMessage.newBuilder(), nodeId, timeout);
    }

    private void publish(SapientMessage.Builder builder, UUID nodeId, Duration timeout)
            throws TimeoutException, InterruptedException {
        builder.setNodeId(nodeId.toString()).setTimestamp(timestampNow());
        NodeWrapper node = nodes.get(nodeId);
        if (node != null) {
            UUID fusionNodeId = node.fusionNodeId.get();
            if (fusionNodeId != null) {
                builder.setDestinationId(fusionNodeId.toString());
            }
        }
        client.publish(ByteBuffer.wrap(builder.build().toByteArray()), timeout);
    }

    private static Timestamp timestampNow() {
        Instant now = Instant.now();
        return Timestamp.newBuilder()
                .setSeconds(now.getEpochSecond())
                .setNanos(now.getNano())
                .build();
    }

    @Override
    public void close() {
        nodes.values().forEach(NodeWrapper::close);
        nodes.clear();
        try {
            client.close();
        } catch (Exception e) {
            logger.log(Level.SEVERE, "failed to close client", e);
        }
    }

    @Override
    public void run() {
        client.run();
    }
}
