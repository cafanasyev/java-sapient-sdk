package io.sapient.transport;

import java.time.Duration;
import java.util.concurrent.TimeoutException;
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
}
