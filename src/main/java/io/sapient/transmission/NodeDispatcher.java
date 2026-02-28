package io.sapient.transmission;

import io.sapient.transport.IClient;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.nio.ByteBuffer;
import java.time.Duration;
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

    private final ConcurrentMap<UUID, NodeWrapper> nodes = new ConcurrentHashMap<>();

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

            if (message.getContentCase() == SapientMessage.ContentCase.REGISTRATION_ACK) {
                UUID destinationId = UUID.fromString(message.getDestinationId());
                NodeWrapper node = nodes.get(destinationId);
                if (node != null && !node.ackQueue.offer(message.getRegistrationAck())) {
                    logger.log(
                            Level.WARNING,
                            "ack queue full, dropping ack for node: {0}",
                            destinationId);
                }
            } else if (message.getContentCase() == SapientMessage.ContentCase.ALERT_ACK) {
                UUID destinationId = UUID.fromString(message.getDestinationId());
                NodeWrapper node = nodes.get(destinationId);
                if (node != null) {
                    node.node.onAlertAck(message.getAlertAck());
                }
            } else if (message.getContentCase() == SapientMessage.ContentCase.TASK) {
                UUID destinationId = UUID.fromString(message.getDestinationId());
                NodeWrapper node = nodes.get(destinationId);
                if (node != null) {
                    node.node.onTask(message.getTask());
                }
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "failed to process incoming message", e);
        }
    }

    @Override
    public void register(INode node) {
        nodes.computeIfAbsent(
                node.getNodeId(),
                k ->
                        new NodeWrapper(
                                node, this, config.onlineCheckInterval(), config.publishTimeout()));
    }

    @Override
    public void unregister(INode node) {
        NodeWrapper wrapper = nodes.remove(node.getNodeId());
        if (wrapper != null) {
            wrapper.close();
            if (wrapper.registered.getAndSet(false)) {
                try {
                    goodbye(node.getNodeId(), config.publishTimeout());
                } catch (TimeoutException | InterruptedException e) {
                    logger.log(
                            Level.SEVERE,
                            "failed to send goodbye for the node: " + node.getNodeId(),
                            e);
                }
            }
        }
    }

    @Override
    public void publish(Registration registration, UUID nodeId, Duration timeout)
            throws TimeoutException, InterruptedException {}

    @Override
    public void publish(StatusReport status, UUID nodeId, Duration timeout)
            throws TimeoutException, InterruptedException {}

    @Override
    public void publish(Alert alert, UUID nodeId, Duration timeout)
            throws TimeoutException, InterruptedException {}

    @Override
    public void publish(DetectionReport detection, UUID nodeId, Duration timeout)
            throws TimeoutException, InterruptedException {}

    @Override
    public void goodbye(UUID nodeId, Duration timeout)
            throws TimeoutException, InterruptedException {}

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
