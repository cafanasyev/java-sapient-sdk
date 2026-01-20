package io.sapient.transport;

import java.io.IOException;
import java.net.Socket;

/** Supplies new {@link Socket} connections, supporting both plain and TLS sockets. */
@FunctionalInterface
public interface SocketProvider {

    /**
     * Creates and returns a new socket connection.
     *
     * @return a connected socket
     * @throws IOException if the connection cannot be established
     */
    Socket get() throws IOException;
}
