package io.sapient.transport;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/** Low-level TCP transport client with publish/subscribe over raw byte buffers. */
public interface IClient extends Runnable, AutoCloseable {

    /**
     * Publishes a message to the connected server.
     *
     * @param msg message bytes to send
     * @param timeout maximum time to wait for the connection and write
     * @throws TimeoutException if the operation exceeds the given timeout
     * @throws InterruptedException if the calling thread is interrupted or the client is stopped
     */
    void publish(ByteBuffer msg, Duration timeout) throws TimeoutException, InterruptedException;

    /**
     * Sets the single consumer that receives messages from the server.
     *
     * @param c consumer to receive incoming byte buffers
     */
    void subscribe(Consumer<ByteBuffer> c);
}
