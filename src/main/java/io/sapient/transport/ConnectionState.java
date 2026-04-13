package io.sapient.transport;

/** Represents the lifecycle state of an {@link IClient} connection. */
public enum ConnectionState {

    /** Not connected; the client may be retrying. */
    DISCONNECTED,

    /** Actively establishing a connection to the remote endpoint. */
    CONNECTING,

    /** Connected and fully operational. */
    CONNECTED,

    /** {@link IClient#close()} has been called; no further reconnects will occur. */
    CLOSED
}
