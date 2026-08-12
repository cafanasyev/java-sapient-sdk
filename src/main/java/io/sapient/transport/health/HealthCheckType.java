package io.sapient.transport.health;

/**
 * How a client checks that the remote endpoint is still alive. One type per client. Not every
 * transport supports every type — the client rejects what it cannot do when it is built.
 */
public enum HealthCheckType {

    /** Opens and closes a throwaway TCP connection, like {@code nc -z host port}. */
    NETCAT,

    /** Runs one ICMP echo through the system {@code ping} binary. Needs ICMP to be allowed. */
    ICMP,

    /**
     * Keepalive solution C. Sends a zero-length frame on the live connection and the peer echoes it
     * back. Not compatible with {@link #PINGPONG} — both use the same ping value but answer
     * differently, so client and server must be set to the same one.
     */
    ECHO,

    /**
     * Keepalive solution D. Sends a zero-length frame on the live connection and the peer answers
     * with a {@code 0xFFFFFFFF} frame. Not compatible with {@link #ECHO}.
     */
    PINGPONG,

    /**
     * Use the keepalive the transport already has, for example HTTP/2 PING on gRPC. A raw socket
     * has none, so {@code SocketClient} rejects this value.
     */
    TRANSPORT_NATIVE
}
