package io.sapient.transport;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import uk.gov.dstl.sapientmsg.bsiflex335v2.SapientMessage;

/** SAPIENT transport client with typed publish/subscribe over {@link SapientMessage}. */
public interface IClient extends AutoCloseable {

    /**
     * Opens the connection and begins receiving messages. Non-blocking — the implementation manages
     * its own connection lifecycle internally. May be called again after {@link #close()} to
     * reconnect.
     */
    void start();

    /**
     * Publishes a SAPIENT message to the connected server.
     *
     * @param msg message to send
     * @param timeout maximum time to wait for the connection and write
     * @throws TimeoutException if the operation exceeds the given timeout
     * @throws InterruptedException if the calling thread is interrupted or the client is stopped
     */
    void publish(SapientMessage msg, Duration timeout)
            throws TimeoutException, InterruptedException;

    /**
     * Sets the single consumer that receives messages from the server.
     *
     * @param c consumer to receive incoming messages
     */
    void subscribe(Consumer<SapientMessage> c);

    /** Returns the current connection state. */
    ConnectionState getState();

    /**
     * Returns {@code true} if the current state is {@link ConnectionState#CONNECTED}. Convenience
     * shorthand for {@code getState() == ConnectionState.CONNECTED}.
     */
    default boolean isConnected() {
        return getState() == ConnectionState.CONNECTED;
    }

    /**
     * Registers a listener that is notified on every state transition. Multiple listeners are
     * supported; each call adds a new listener without replacing existing ones.
     *
     * <p>Listeners are invoked on a background thread. Implementations must protect the run-loop
     * from listener exceptions.
     *
     * @param listener biconsumer called with the new {@link ConnectionState} and the {@link
     *     Instant} of the transition on each state change
     */
    void addStateChangeListener(BiConsumer<ConnectionState, Instant> listener);

    /**
     * Removes a previously registered state-change listener. No-op if the listener was not
     * registered.
     *
     * @param listener listener to remove
     */
    void removeStateChangeListener(BiConsumer<ConnectionState, Instant> listener);

    /**
     * Probes whether the remote endpoint is reachable without affecting the managed connection. The
     * probe mechanism is transport-specific:
     *
     * <ul>
     *   <li>TCP: opens and immediately closes a TCP connection to the target address (equivalent to
     *       {@code nc -z host port})
     *   <li>gRPC: performs a connectivity check or HTTP/2 PING
     * </ul>
     *
     * @param timeout maximum time to wait for the probe
     * @return {@code true} if the endpoint accepted the probe connection
     */
    boolean probeReachable(Duration timeout);
}
