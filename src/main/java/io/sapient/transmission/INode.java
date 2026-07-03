package io.sapient.transmission;

import java.util.UUID;
import uk.gov.dstl.sapientmsg.bsiflex335v2.*;
import uk.gov.dstl.sapientmsg.bsiflex335v2.Error;

/** Represents a SAPIENT edge or fusion node. */
public interface INode {

    /**
     * Returns {@code true} if the node is currently online.
     * Used by Node Dispatcher to understand is node available
     * and can be registered. And otherwise - to understand did
     * non become unavailable and need to send GOOD BYE Status
     * report to Fusion Node.
     *
     * @return online status
     */
    boolean isOnline();

    /**
     * Returns the unique identifier of this node.
     *
     * @return node id
     */
    UUID getNodeId();

    /**
     * Returns the registration message for this node.
     *
     * @return registration message
     */
    Registration getRegistration();

    /**
     * Returns the current status report for this node.
     *
     * @return status report
     */
    StatusReport getStatusReport();

    /**
     * Called when a {@link RegistrationAck} is received for this node.
     *
     * @param ack the acknowledgement from the fusion node
     */
    void onRegistrationAck(RegistrationAck ack);

    /**
     * Called when an {@link AlertAck} is received for this node.
     *
     * @param ack the alert acknowledgement from the fusion node
     */
    void onAlertAck(AlertAck ack);

    /**
     * Called when a {@link Task} is received for this node.
     *
     * @param task the task assigned by the fusion node
     */
    void onTask(Task task);

    /**
     * Called when an {@link Error} is received for this node.
     *
     * @param error the error reported by the fusion node
     */
    void onError(Error error);
}
