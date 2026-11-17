package io.sapient.transmission;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import uk.gov.dstl.sapientmsg.bsiflex335v2.Alert;
import uk.gov.dstl.sapientmsg.bsiflex335v2.DetectionReport;
import uk.gov.dstl.sapientmsg.bsiflex335v2.Registration;
import uk.gov.dstl.sapientmsg.bsiflex335v2.SapientMessage;
import uk.gov.dstl.sapientmsg.bsiflex335v2.StatusReport;
import uk.gov.dstl.sapientmsg.bsiflex335v2.TaskAck;

/**
 * Manages SAPIENT node lifecycles and routes typed Protobuf messages through an {@link
 * io.sapient.transport.IClient}.
 */
public interface INodeDispatcher extends AutoCloseable {

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
     * Publishes a {@link Registration} message for the given node. The server starts the node over
     * with a registration, so the next status report of the node goes out as INFO_NEW.
     *
     * @param registration the registration message
     * @param nodeId the source node identifier
     * @param timeout maximum time to wait, including the wait for a status report of the same node
     *     that is still being sent
     * @return the {@link SapientMessage} as actually sent
     * @throws TimeoutException if the operation exceeds the given timeout
     * @throws InterruptedException if the calling thread is interrupted
     */
    SapientMessage publish(Registration registration, UUID nodeId, Duration timeout)
            throws TimeoutException, InterruptedException;

    /**
     * Publishes a {@link StatusReport} message for the given node. A goodbye always goes out as
     * INFO_NEW. For any other report, unless the caller sets {@link StatusReport.Info} to
     * INFO_UNCHANGED, the field is filled in: INFO_UNCHANGED when the content repeats the last
     * report the server is known to have from this node, INFO_NEW otherwise. Status reports of one
     * node go out one at a time, so the comparison follows the order in which the server receives
     * them.
     *
     * @param status the status report
     * @param nodeId the source node identifier
     * @param timeout maximum time to wait, including the wait for an earlier status report of the
     *     same node
     * @return the {@link SapientMessage} as actually sent
     * @throws TimeoutException if the operation exceeds the given timeout
     * @throws InterruptedException if the calling thread is interrupted
     */
    SapientMessage publish(StatusReport status, UUID nodeId, Duration timeout)
            throws TimeoutException, InterruptedException;

    /**
     * Publishes a {@link TaskAck} message for the given node.
     *
     * @param taskAck the task acknowledgement
     * @param nodeId the source node identifier
     * @param timeout maximum time to wait
     * @return the {@link SapientMessage} as actually sent
     * @throws TimeoutException if the operation exceeds the given timeout
     * @throws InterruptedException if the calling thread is interrupted
     */
    SapientMessage publish(TaskAck taskAck, UUID nodeId, Duration timeout)
            throws TimeoutException, InterruptedException;

    /**
     * Publishes an {@link Alert} message for the given node.
     *
     * @param alert the alert message
     * @param nodeId the source node identifier
     * @param timeout maximum time to wait
     * @return the {@link SapientMessage} as actually sent
     * @throws TimeoutException if the operation exceeds the given timeout
     * @throws InterruptedException if the calling thread is interrupted
     */
    SapientMessage publish(Alert alert, UUID nodeId, Duration timeout)
            throws TimeoutException, InterruptedException;

    /**
     * Publishes a {@link DetectionReport} message for the given node.
     *
     * @param detection the detection report
     * @param nodeId the source node identifier
     * @param timeout maximum time to wait
     * @return the {@link SapientMessage} as actually sent
     * @throws TimeoutException if the operation exceeds the given timeout
     * @throws InterruptedException if the calling thread is interrupted
     */
    SapientMessage publish(DetectionReport detection, UUID nodeId, Duration timeout)
            throws TimeoutException, InterruptedException;
}
