package io.sapient.transport;

import java.io.IOException;
import java.net.Socket;

/**
 * Describes a remote TCP endpoint and supplies new {@link Socket} connections to it.
 *
 * <p>Carrying the host and port explicitly allows the client to perform reachability probes
 * independently of the managed connection.
 */
public interface SocketProvider {

    /**
     * Creates and returns a new socket connection to the endpoint.
     *
     * @return a connected socket
     * @throws IOException if the connection cannot be established
     */
    Socket get() throws IOException;

    /**
     * Returns the hostname or IP address of the remote endpoint.
     *
     * @return the remote hostname or IP address
     */
    String host();

    /**
     * Returns the TCP port of the remote endpoint.
     *
     * @return the remote TCP port
     */
    int port();
}
