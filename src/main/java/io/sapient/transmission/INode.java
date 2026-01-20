package io.sapient.transmission;

import java.util.UUID;
import uk.gov.dstl.sapientmsg.bsiflex335v2.*;

/** Represents a SAPIENT edge or fusion node. */
public interface INode {

    /**
     * Returns {@code true} if the node is currently online.
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
     * Called when a {@link Task} is received for this node.
     *
     * @param task the task assigned by the fusion node
     */
    void onTask(Task task);
}
