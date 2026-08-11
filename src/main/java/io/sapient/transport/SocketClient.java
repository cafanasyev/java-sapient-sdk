package io.sapient.transport;

import com.google.protobuf.InvalidProtocolBufferException;
import io.sapient.transport.health.HealthCheckConfig;
import io.sapient.transport.health.HealthCheckType;
import io.sapient.transport.health.HealthMonitor;
import io.sapient.transport.health.IHealthCheck;
import io.sapient.transport.health.IcmpHealthCheck;
import io.sapient.transport.health.InBandHealthCheck;
import io.sapient.transport.health.KeepalivePrefixes;
import io.sapient.transport.health.NetcatHealthCheck;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import uk.gov.dstl.sapientmsg.bsiflex335v2.SapientMessage;

/**
 * TCP client with automatic reconnection, thread-safe publishing, and single-consumer subscription.
 * Messages are framed with a 4-byte little-endian length prefix as required by BSI Flex 335 v2.0
 * §4.2. Serialization and deserialization of {@link SapientMessage} is handled internally.
 */
@Slf4j
public class SocketClient implements IClient {

    private static class Connection implements AutoCloseable {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private Connection(ISocketProvider socketProvider) throws IOException {
            socket = socketProvider.get();
            in = socket.getInputStream();
            out = socket.getOutputStream();
        }

        /**
         * Closes the socket, at most once. The run loop, the watchdog and {@link
         * SocketClient#close()} all reach for the same connection, so the flag keeps them from
         * closing the socket several times over.
         */
        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            log.info("closing socket");
            // SSLSocket.close() can block on a dead TLS connection; don't wait for it.
            Thread.startVirtualThread(
                    () -> {
                        try {
                            socket.close();
                            log.info("socket closed");
                        } catch (IOException e) {
                            log.error("failed to close socket", e);
                        }
                    });
        }

        /**
         * Returns {@code true} while this connection can still carry a message. {@link
         * Socket#isConnected()} alone is not enough: it stays {@code true} for the rest of the
         * socket's life once the connection was established, closed or not.
         */
        private boolean isUsable() {
            return !closed.get() && socket.isConnected() && !socket.isClosed();
        }
    }

    private static final Duration DEFAULT_INITIAL_RECONNECT_DELAY = Duration.ofSeconds(1);

    /** How long {@link #close()} waits for the run loop to finish before giving up on it. */
    private static final Duration RUN_LOOP_STOP_TIMEOUT = Duration.ofSeconds(5);

    /**
     * Largest message body we will allocate for by default, in bytes. A garbage or hostile length
     * prefix cannot make the client allocate more than this. Same value as the SAPIENT server uses.
     */
    static final int DEFAULT_MAX_FRAME_SIZE = 16 << 20;

    private final ISocketProvider socketProvider;
    private final HealthCheckConfig healthCheckConfig;
    private final Duration initialReconnectDelay;
    private final int maxFrameSize;

    /** Null when the health check puts nothing on the wire, which is the NETCAT and ICMP case. */
    private final KeepalivePrefixes prefixes;

    /**
     * Creates a client with the default health check: a TCP connect probe every 10 seconds, a 2
     * second probe timeout, and 3 failures in a row before the connection is dropped.
     *
     * @param socketProvider supplies new socket connections and describes the remote address
     */
    public SocketClient(@NonNull ISocketProvider socketProvider) {
        this(socketProvider, HealthCheckConfig.DEFAULT, DEFAULT_INITIAL_RECONNECT_DELAY);
    }

    /**
     * Creates a client with an explicit health check.
     *
     * @param socketProvider supplies new socket connections and describes the remote address
     * @param healthCheckConfig which liveness check to run, how often, and how many failures in a
     *     row end the connection
     * @param initialReconnectDelay base delay for the first reconnect attempt; scaled linearly by
     *     attempt count (capped at 10x)
     * @throws IllegalArgumentException if the health check type cannot run over a raw socket
     */
    public SocketClient(
            @NonNull ISocketProvider socketProvider,
            @NonNull HealthCheckConfig healthCheckConfig,
            @NonNull Duration initialReconnectDelay) {
        this(socketProvider, healthCheckConfig, initialReconnectDelay, DEFAULT_MAX_FRAME_SIZE);
    }

    /**
     * Creates a client with a configurable health check and frame size limit.
     *
     * @param socketProvider supplies new socket connections and describes the remote address
     * @param healthCheckConfig how the connection is checked for liveness
     * @param initialReconnectDelay base delay for the first reconnect attempt; scaled linearly by
     *     attempt count (capped at 10x)
     * @param maxFrameSize largest message body accepted, in bytes. A length prefix above this drops
     *     the connection instead of allocating for it. Must be positive
     * @throws IllegalArgumentException if the health check type cannot run over a raw socket, or if
     *     {@code maxFrameSize} is not positive
     */
    public SocketClient(
            @NonNull ISocketProvider socketProvider,
            @NonNull HealthCheckConfig healthCheckConfig,
            @NonNull Duration initialReconnectDelay,
            int maxFrameSize) {
        if (healthCheckConfig.type() == HealthCheckType.TRANSPORT_NATIVE) {
            throw new IllegalArgumentException(
                    "SocketClient does not support TRANSPORT_NATIVE: a raw socket has no built-in"
                            + " keepalive");
        }
        if (maxFrameSize <= 0) {
            throw new IllegalArgumentException("maxFrameSize must be positive: " + maxFrameSize);
        }
        this.socketProvider = socketProvider;
        this.healthCheckConfig = healthCheckConfig;
        this.initialReconnectDelay = initialReconnectDelay;
        this.maxFrameSize = maxFrameSize;
        this.prefixes = KeepalivePrefixes.of(healthCheckConfig.type());
    }

    private final AtomicBoolean running = new AtomicBoolean(false);

    // connection slot: empty = disconnected, contains connection = available for publishing
    // capacity 1 acts as both connection gate and publish mutex
    private final BlockingQueue<Connection> connectionSlot = new ArrayBlockingQueue<>(1, true);

    private final AtomicReference<Consumer<SapientMessage>> consumer = new AtomicReference<>();

    private final AtomicReference<ConnectionState> state =
            new AtomicReference<>(ConnectionState.DISCONNECTED);

    private final List<BiConsumer<ConnectionState, Instant>> stateListeners = new ArrayList<>();
    private final ReadWriteLock listenersLock = new ReentrantReadWriteLock();

    // the thread running runLoop(), or null while no run loop is alive. Cleared by close()
    // only after the loop has actually terminated, so a client can never end up with two.
    private volatile Thread runLoopThread;

    @Override
    public void start() {
        if (runLoopThread != null) {
            log.warn("client is already running");
            return;
        }
        if (running.compareAndSet(false, true)) {
            runLoopThread = Thread.startVirtualThread(this::runLoop);
        }
    }

    private void runLoop() {
        long reconnectAttempts = 0;

        while (running.get()) {
            // probe before attempting a real connection. If the server is not
            // reachable we stay silent — no CONNECTING → DISCONNECTED churn for
            // listeners to chew on, which also means the dispatcher records the
            // outage start at the moment the connection actually died rather than
            // at the latest failed retry.
            // a plain TCP probe before every connect attempt, whatever the health check type
            // is: we are about to open a TCP connection anyway, so this is always the right
            // question to ask. Staying silent when it fails keeps CONNECTING → DISCONNECTED
            // churn out of the listeners.
            if (!probeReachable(healthCheckConfig.timeout())) {
                sleepBeforeReconnect(++reconnectAttempts);
                continue;
            }

            log.info("initializing new server connection");
            setState(ConnectionState.CONNECTING);

            Thread monitorThread = null;
            Connection conn = null;
            try {
                conn = connect();
                reconnectAttempts = 0;
                setState(ConnectionState.CONNECTED);
                // one monitor per connection; closing the socket is how it reports a dead link
                HealthMonitor monitor =
                        new HealthMonitor(newHealthCheck(), healthCheckConfig, conn::close);
                monitorThread = Thread.startVirtualThread(monitor);
                readLoop(conn, monitor);
            } catch (EOFException e) {
                if (running.get()) {
                    log.info("server closed the connection");
                }
            } catch (IOException e) {
                if (running.get()) {
                    log.error("server connection failure", e);
                }
            } catch (RuntimeException e) {
                // an unchecked exception here would otherwise escape runLoop and kill the
                // virtual thread. running stays true and runLoopThread stays non-null, so
                // start() refuses to build a replacement and the client is dead for good.
                // Treat it like any other connection failure: drop it and reconnect.
                if (running.get()) {
                    log.error("unexpected failure on the connection", e);
                }
            } finally {
                if (monitorThread != null) {
                    monitorThread.interrupt();
                }
                if (conn != null) {
                    // take the connection out of the slot before closing it, or a publisher
                    // would pick up a dead connection and write into a closed socket
                    connectionSlot.remove(conn);
                    conn.close();
                }
                setState(ConnectionState.DISCONNECTED);
            }

            if (!running.get()) {
                break;
            }

            sleepBeforeReconnect(++reconnectAttempts);
        }

        log.info("client stopped gracefully");
    }

    private void sleepBeforeReconnect(long reconnectAttempts) {
        long multiplier = Math.min(reconnectAttempts, 10);
        Duration delay = initialReconnectDelay.multipliedBy(multiplier);
        log.info("sleep {} before reconnecting to the server", delay);
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException e) {
            log.info("reconnect sleep interrupted", e);
        }
    }

    /**
     * Stops the client and waits for its run loop to terminate. Safe to call several times and from
     * several threads; only the first call has an effect. Waiting for the loop is what makes {@link
     * #start()} safe to call afterwards — a client closed while its run loop sat in a reconnect
     * backoff would otherwise be left with two loops competing for the same connection.
     */
    @Override
    public void close() {
        log.info("stopping client");
        running.set(false);
        Thread loop = runLoopThread;
        if (loop != null) {
            loop.interrupt();
        }
        Connection conn = connectionSlot.poll();
        if (conn != null) {
            conn.close();
        }
        awaitRunLoopStop(loop);
        setState(ConnectionState.CLOSED);
    }

    private void awaitRunLoopStop(Thread loop) {
        if (loop == null || loop == Thread.currentThread()) {
            return;
        }
        try {
            loop.join(RUN_LOOP_STOP_TIMEOUT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("interrupted while waiting for the run loop to stop");
            return;
        }
        if (loop.isAlive()) {
            // leave runLoopThread set so start() refuses to add a second loop on top of it
            log.error("run loop did not stop within {}", RUN_LOOP_STOP_TIMEOUT);
        } else {
            runLoopThread = null;
        }
    }

    /***
     * Blocking method to publish a SAPIENT message to the server socket.
     * Each message is framed with a 4-byte little-endian length prefix followed by the serialized
     * message bytes, as required by the SAPIENT protocol (BSI Flex 335 v2.0 §4.2).
     * The connectionSlot queue acts as both a connection-availability gate (empty when
     * disconnected) and a publishing mutex (only one publisher can hold the connection at a time).
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
    public void publish(SapientMessage msg, Duration timeout)
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

            if (!conn.isUsable()) {
                continue;
            }

            try {
                byte[] bytes = msg.toByteArray();
                int len = bytes.length;
                byte[] frame =
                        ByteBuffer.allocate(4 + len)
                                .order(ByteOrder.LITTLE_ENDIAN)
                                .putInt(len)
                                .put(bytes)
                                .array();
                conn.out.write(frame);
                conn.out.flush();
                if (conn.isUsable() && !connectionSlot.offer(conn)) {
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
     * Sets the single consumer per SocketClient. Each subsequent method invocation will
     * replace the previous consumer with a new.
     *
     * @param c - new consumer to read messages from server socket
     *
     */
    @Override
    public void subscribe(Consumer<SapientMessage> c) {
        consumer.set(c);
    }

    @Override
    public ConnectionState getState() {
        return state.get();
    }

    @Override
    public Duration connectionLossDetectionDelay() {
        return healthCheckConfig.connectionLossDetectionDelay();
    }

    @Override
    public void addStateChangeListener(BiConsumer<ConnectionState, Instant> listener) {
        listenersLock.writeLock().lock();
        try {
            stateListeners.add(listener);
        } finally {
            listenersLock.writeLock().unlock();
        }
    }

    @Override
    public void removeStateChangeListener(BiConsumer<ConnectionState, Instant> listener) {
        listenersLock.writeLock().lock();
        try {
            stateListeners.remove(listener);
        } finally {
            listenersLock.writeLock().unlock();
        }
    }

    @Override
    public boolean probeReachable(Duration timeout) {
        try (Socket probe = new Socket()) {
            probe.connect(
                    new InetSocketAddress(socketProvider.host(), socketProvider.port()),
                    (int) timeout.toMillis());
            return true;
        } catch (IOException | IllegalArgumentException e) {
            return false;
        }
    }

    private void setState(ConnectionState next) {
        ConnectionState prev = state.getAndSet(next);
        if (prev == next) {
            return;
        }
        Instant ts = Instant.now();
        listenersLock.readLock().lock();
        try {
            for (BiConsumer<ConnectionState, Instant> listener : stateListeners) {
                try {
                    listener.accept(next, ts);
                } catch (Exception e) {
                    log.error("state change listener threw exception", e);
                }
            }
        } finally {
            listenersLock.readLock().unlock();
        }
    }

    private Connection connect() throws IOException {
        connectionSlot.poll();
        Connection newConnection = new Connection(socketProvider);
        if (!connectionSlot.offer(newConnection)) {
            throw new RuntimeException("failed to add connection to slot");
        }

        return newConnection;
    }

    private void readLoop(Connection connection, HealthMonitor monitor) throws IOException {
        byte[] lenBuf = new byte[4];

        while (running.get()) {
            readFully(connection.in, lenBuf, 4);
            int len = ByteBuffer.wrap(lenBuf).order(ByteOrder.LITTLE_ENDIAN).getInt();

            // a keepalive frame has no body, so it can never become a SapientMessage.
            // We consume it and never answer it — the client only ever initiates.
            if (prefixes != null && (len == prefixes.ping() || len == prefixes.pong())) {
                monitor.onInbound();
                continue;
            }

            checkFrameSize(len, maxFrameSize);
            byte[] msgBuf = new byte[len];
            readFully(connection.in, msgBuf, len);

            // any frame from the peer proves the pipe is alive, not only a pong
            monitor.onInbound();

            Consumer<SapientMessage> cons = consumer.get();
            if (cons != null) {
                try {
                    cons.accept(SapientMessage.parseFrom(msgBuf));
                } catch (InvalidProtocolBufferException e) {
                    log.error("failed to parse incoming message", e);
                }
            }
        }
    }

    /**
     * Builds the health check for one connection. The monitor that drives it closes the socket
     * after enough failed checks in a row, which unblocks the pending {@code InputStream.read()}
     * and drives the {@link ConnectionState#DISCONNECTED} transition through the existing path in
     * {@link #runLoop}. Detection does not depend on {@code SO_TIMEOUT}, which is not reliable on
     * TLS sockets or when bytes trickle in.
     */
    private IHealthCheck newHealthCheck() {
        Duration timeout = healthCheckConfig.timeout();
        return switch (healthCheckConfig.type()) {
            case NETCAT ->
                    new NetcatHealthCheck(socketProvider.host(), socketProvider.port(), timeout);
            case ICMP -> new IcmpHealthCheck(socketProvider.host(), timeout);
            case ECHO, PINGPONG ->
                    new InBandHealthCheck(prefixes.ping(), timeout, this::writePrefix);
            case TRANSPORT_NATIVE -> throw new IllegalStateException("rejected in the constructor");
        };
    }

    /**
     * Writes a bare 4-byte prefix on the live connection for the in-band health check.
     *
     * <p>It queues on {@code connectionSlot} like any publisher, so a write already in progress is
     * never interrupted. Returning {@code false} when the slot cannot be taken in time is what
     * turns a blocked write path into a failed check.
     */
    private boolean writePrefix(int prefix, Duration timeout) {
        Connection conn;
        try {
            conn = connectionSlot.poll(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        if (conn == null || !conn.isUsable()) {
            return false;
        }
        try {
            conn.out.write(
                    ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(prefix).array());
            conn.out.flush();
            if (conn.isUsable() && !connectionSlot.offer(conn)) {
                log.error("failed to return connection to slot");
            }
            return true;
        } catch (IOException e) {
            log.error("failed to write keepalive prefix", e);
            // don't put back — the slot stays empty until reconnect re-offers, same as publish
            return false;
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

    /**
     * Rejects a length prefix that no real SAPIENT message can have. The prefix is unsigned on the
     * wire but signed in Java, so it is widened before the comparison — without that, {@code
     * 0xFFFFFFFF} reads as -1, passes any {@code > MAX} test, and then blows up in {@code new
     * byte[len]} with an unchecked NegativeArraySizeException.
     *
     * @param len length prefix as read from the wire
     * @param maxFrameSize largest body accepted, in bytes
     * @throws IOException if the body would be larger than {@code maxFrameSize}
     */
    static void checkFrameSize(int len, int maxFrameSize) throws IOException {
        long size = Integer.toUnsignedLong(len);
        if (size > maxFrameSize) {
            throw new IOException(
                    "frame too large: " + size + " bytes, max " + maxFrameSize + " bytes");
        }
    }
}
