package io.sapient.transport;

import com.google.protobuf.InvalidProtocolBufferException;
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

        private Connection(ISocketProvider socketProvider) throws IOException {
            socket = socketProvider.get();
            in = socket.getInputStream();
            out = socket.getOutputStream();
        }

        @Override
        public void close() {
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

        private boolean isConnected() {
            return socket.isConnected();
        }
    }

    private static final Duration DEFAULT_PROBE_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration DEFAULT_INITIAL_RECONNECT_DELAY = Duration.ofSeconds(1);
    private static final Duration DEFAULT_WATCHDOG_INTERVAL = Duration.ofSeconds(10);

    private final ISocketProvider socketProvider;
    private final Duration probeTimeout;
    private final Duration initialReconnectDelay;
    private final Duration watchdogInterval;

    /**
     * Creates a client that obtains connections from the given provider.
     *
     * @param socketProvider supplies new socket connections and describes the remote address
     */
    public SocketClient(@NonNull ISocketProvider socketProvider) {
        this(
                socketProvider,
                DEFAULT_PROBE_TIMEOUT,
                DEFAULT_INITIAL_RECONNECT_DELAY,
                DEFAULT_WATCHDOG_INTERVAL);
    }

    /**
     * Creates a client with configurable timeouts.
     *
     * @param socketProvider supplies new socket connections and describes the remote address
     * @param probeTimeout timeout for reachability probes (applies to both watchdog probes and the
     *     public {@link #probeReachable(Duration)} call)
     * @param initialReconnectDelay base delay for the first reconnect attempt; scaled linearly by
     *     attempt count (capped at 10x)
     * @param watchdogInterval how often the watchdog runs a reachability probe while the connection
     *     is established. Detection of a silently-dead peer is bounded by {@code watchdogInterval +
     *     probeTimeout}
     */
    public SocketClient(
            @NonNull ISocketProvider socketProvider,
            @NonNull Duration probeTimeout,
            @NonNull Duration initialReconnectDelay,
            @NonNull Duration watchdogInterval) {
        this.socketProvider = socketProvider;
        this.probeTimeout = probeTimeout;
        this.initialReconnectDelay = initialReconnectDelay;
        this.watchdogInterval = watchdogInterval;
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

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            Thread.startVirtualThread(this::runLoop);
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
            if (!probeReachable(probeTimeout)) {
                sleepBeforeReconnect(++reconnectAttempts);
                continue;
            }

            log.info("initializing new server connection");
            setState(ConnectionState.CONNECTING);

            Thread watchdog = null;
            try (Connection conn = connect()) {
                reconnectAttempts = 0;
                setState(ConnectionState.CONNECTED);
                watchdog = startWatchdog(conn);
                readLoop(conn);
            } catch (EOFException e) {
                if (running.get()) {
                    log.info("server closed the connection");
                }
            } catch (IOException e) {
                if (running.get()) {
                    log.error("server connection failure", e);
                }
            } finally {
                if (watchdog != null) {
                    watchdog.interrupt();
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

    @Override
    public void close() {
        log.info("stopping client");
        running.set(false);
        setState(ConnectionState.CLOSED);
        Connection conn = connectionSlot.poll();
        if (conn != null) {
            conn.close();
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

            if (!conn.isConnected()) {
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

    private void readLoop(Connection connection) throws IOException {
        byte[] lenBuf = new byte[4];

        while (running.get()) {
            readFully(connection.in, lenBuf, 4);
            int len = ByteBuffer.wrap(lenBuf).order(ByteOrder.LITTLE_ENDIAN).getInt();
            byte[] msgBuf = new byte[len];
            readFully(connection.in, msgBuf, len);

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
     * Watches the connection by periodically running a reachability probe independently of the read
     * path. On failure the watchdog closes the socket, which unblocks {@link #readLoop} (via {@code
     * IOException}) and drives the {@link ConnectionState#DISCONNECTED} transition in {@link
     * #runLoop}. This removes any reliance on {@code SO_TIMEOUT} firing promptly in {@link
     * java.io.InputStream#read()}, which is not reliable on SSL sockets or when data trickles in
     * slowly.
     */
    private Thread startWatchdog(Connection conn) {
        return Thread.startVirtualThread(
                () -> {
                    while (running.get() && !Thread.currentThread().isInterrupted()) {
                        try {
                            Thread.sleep(watchdogInterval);
                        } catch (InterruptedException e) {
                            return;
                        }
                        if (!running.get() || Thread.currentThread().isInterrupted()) {
                            return;
                        }
                        if (!probeReachable(probeTimeout)) {
                            log.warn("watchdog probe failed, closing connection");
                            conn.close();
                            return;
                        }
                    }
                });
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
