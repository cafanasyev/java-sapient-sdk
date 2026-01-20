package io.sapient.transmission;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import uk.gov.dstl.sapientmsg.bsiflex335v2.Alert;
import uk.gov.dstl.sapientmsg.bsiflex335v2.DetectionReport;
import uk.gov.dstl.sapientmsg.bsiflex335v2.Registration;
import uk.gov.dstl.sapientmsg.bsiflex335v2.StatusReport;

/**
 * Manages SAPIENT node lifecycles and routes typed Protobuf messages through an {@link
 * io.sapient.transport.IClient}.
 */
public interface INodeDispatcher extends Runnable, AutoCloseable {

    /**
     * Registers a node with this dispatcher.
     *
     * @param node the node to register
     */
    void register(INode node);

    /**
     * Unregisters a node, sending a goodbye message if it was previously registered.
     *
     * @param node the node to unregister
     */
    void unregister(INode node);

    /**
     * Publishes a {@link Registration} message for the given node.
     *
     * @param registration the registration message
     * @param nodeId the source node identifier
     * @param timeout maximum time to wait
     * @throws TimeoutException if the operation exceeds the given timeout
     * @throws InterruptedException if the calling thread is interrupted
     */
    void publish(Registration registration, UUID nodeId, Duration timeout)
            throws TimeoutException, InterruptedException;

    /**
     * Publishes a {@link StatusReport} message for the given node.
     *
     * @param status the status report
     * @param nodeId the source node identifier
     * @param timeout maximum time to wait
     * @throws TimeoutException if the operation exceeds the given timeout
     * @throws InterruptedException if the calling thread is interrupted
     */
    void publish(StatusReport status, UUID nodeId, Duration timeout)
            throws TimeoutException, InterruptedException;

    /**
     * Publishes an {@link Alert} message for the given node.
     *
     * @param alert the alert message
     * @param nodeId the source node identifier
     * @param timeout maximum time to wait
     * @throws TimeoutException if the operation exceeds the given timeout
     * @throws InterruptedException if the calling thread is interrupted
     */
    void publish(Alert alert, UUID nodeId, Duration timeout)
            throws TimeoutException, InterruptedException;

    /**
     * Publishes a {@link DetectionReport} message for the given node.
     *
     * @param detection the detection report
     * @param nodeId the source node identifier
     * @param timeout maximum time to wait
     * @throws TimeoutException if the operation exceeds the given timeout
     * @throws InterruptedException if the calling thread is interrupted
     */
    void publish(DetectionReport detection, UUID nodeId, Duration timeout)
            throws TimeoutException, InterruptedException;

    /**
     * Sends a goodbye message for the given node.
     *
     * @param nodeId the node identifier
     * @param timeout maximum time to wait
     * @throws TimeoutException if the operation exceeds the given timeout
     * @throws InterruptedException if the calling thread is interrupted
     */
    void goodbye(UUID nodeId, Duration timeout) throws TimeoutException, InterruptedException;
}
