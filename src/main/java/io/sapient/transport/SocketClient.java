package io.sapient.transport;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

/**
 * TCP client with automatic reconnection, thread-safe publishing, and single-consumer subscription.
 */
@Slf4j
public class SocketClient implements IClient, Runnable {

    private static class Connection implements AutoCloseable {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;

        private Connection(SocketProvider socketProvider) throws IOException {
            socket = socketProvider.get();
            in = socket.getInputStream();
            out = socket.getOutputStream();
        }

        @Override
        public void close() {
            log.info("closing socket");
            try {
                socket.close();
            } catch (IOException e) {
                log.error("failed to close socket", e);
            }
            log.info("socket closed");
        }

        private boolean isConnected() {
            return socket.isConnected();
        }
    }

    private final SocketProvider socketProvider;

    /**
     * Creates a client that obtains connections from the given provider.
     *
     * @param socketProvider supplier of new socket connections
     */
    public SocketClient(@NonNull SocketProvider socketProvider) {
        this.socketProvider = socketProvider;
    }

    private final AtomicBoolean running = new AtomicBoolean(false);

    // connection slot: empty = disconnected, contains connection = available for publishing
    // capacity 1 acts as both connection gate and publish mutex
    private final BlockingQueue<Connection> connectionSlot = new ArrayBlockingQueue<>(1, true);

    private final AtomicReference<Consumer<ByteBuffer>> consumer = new AtomicReference<>();

    @Override
    public void run() {
        running.set(true);
        long reconnectAttempts = 0;

        while (running.get()) {
            log.info("initializing new server connection");

            try (Connection conn = connect()) {
                reconnectAttempts = 0;
                readLoop(conn);
            } catch (EOFException e) {
                if (running.get()) {
                    log.info("server closed the connection");
                }
            } catch (IOException e) {
                if (running.get()) {
                    log.error("server connection failure", e);
                }
            }

            if (!running.get()) {
                break;
            }

            long delay = Math.min(++reconnectAttempts, 10);

            log.info("sleep {}s before reconnecting to the server", delay);

            try {
                TimeUnit.SECONDS.sleep(delay);
            } catch (InterruptedException e) {
                log.info("reconnect sleep interrupted", e);
            }
        }

        log.info("client stopped gracefully");
    }

    @Override
    public void close() {
        log.info("stopping client");
        running.set(false);
        Connection conn = connectionSlot.poll();
        if (conn != null) {
            conn.close();
        }
    }

    /***
     *  Blocking method to publish a message to the server socket.
     *  Each message is framed with a 4-byte big-endian length prefix followed by the message bytes,
     *  as required by the SAPIENT protocol (BSI Flex 335 v2.0 §4.2).
     *  The connectionSlot queue acts as both a connection-availability gate (empty when
     *  disconnected) and a publishing mutex (only one publisher can hold the connection at a time).
     *
     * @param msg - message to be sent to the server.
     * @param timeout - timeout for waiting on both publish serialization and connection availability.
     * @throws TimeoutException - will be thrown if publish is blocked by other threads for period above
     *                          the specified timeout or SocketClient is unable to reconnect to the server
     *                          socket within the specified timeout.
     * @throws InterruptedException - will be thrown if the calling Thread is interrupted or the SocketClient
     *                              is stopped.
     */
    @Override
    @ThreadSafe
    public void publish(ByteBuffer msg, Duration timeout)
            throws TimeoutException, InterruptedException {
        if (!running.get()) {
            throw new InterruptedException("SocketClient has been stopped");
        }

        long deadline = System.nanoTime() + timeout.toNanos();

        // trying to reconnect and write message only until timeout reached or SocketClient is
        // stopped
        while (running.get()) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new TimeoutException("publish timeout");
            }

            Connection conn = connectionSlot.poll(remaining, TimeUnit.NANOSECONDS);
            if (conn == null) {
                throw new TimeoutException("publish timeout");
            }

            if (!conn.isConnected()) {
                continue;
            }

            try {
                int len = msg.remaining();
                byte[] frame =
                        ByteBuffer.allocate(4 + len)
                                .putInt(len)
                                .put(msg.array(), msg.arrayOffset() + msg.position(), len)
                                .array();
                conn.out.write(frame);
                conn.out.flush();
                if (conn.isConnected() && !connectionSlot.offer(conn)) {
                    log.error("failed to return connection to slot");
                }
                return;
            } catch (IOException e) {
                log.error("failed to publish message to the server", e);
                // don't put back — slot stays empty until reconnect re-offers
            }
        }

        throw new InterruptedException("SocketClient has been stopped");
    }

    /***
     *  Sets the single consumer per SocketClient. Each subsequent method invocation will
     *  replace the previous consumer with a new.
     *
     * @param c - new consumer to read messages from server socket
     *
     */
    @Override
    public void subscribe(Consumer<ByteBuffer> c) {
        consumer.set(c);
    }

    private Connection connect() throws IOException {
        connectionSlot.poll();
        Connection newConnection = new Connection(socketProvider);
        if (!connectionSlot.offer(newConnection)) {
            throw new RuntimeException("failed to add connection to slot");
        }

        return newConnection;
    }

    private void readLoop(Connection connection) throws IOException {
        byte[] lenBuf = new byte[4];

        while (connection.isConnected()) {
            readFully(connection.in, lenBuf, 4);
            int len = ByteBuffer.wrap(lenBuf).getInt();
            byte[] msgBuf = new byte[len];
            readFully(connection.in, msgBuf, len);

            Consumer<ByteBuffer> cons = consumer.get();
            if (cons != null) {
                cons.accept(ByteBuffer.wrap(msgBuf));
            }
        }
    }

    private void readFully(InputStream in, byte[] buf, int count) throws IOException {
        int total = 0;
        while (total < count) {
            int n = in.read(buf, total, count - total);
            if (n < 0) {
                throw new EOFException("connection to the server lost");
            }
            total += n;
        }
    }
}
