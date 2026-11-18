package io.sapient.testing;

import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.protobuf.InvalidProtocolBufferException;
import io.sapient.transport.ISocketProvider;
import io.sapient.transport.health.KeepalivePrefixes;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.mockito.stubbing.Answer;
import uk.gov.dstl.sapientmsg.bsiflex335v2.SapientMessage;

/**
 * The fusion node's end of in-memory sockets. Frames the client writes become messages for the
 * fusion node, and what the fusion node sends goes out framed, so {@code SocketClient} runs its own
 * read and write code. Host and port name a real listener on the loopback address that only answers
 * probes.
 */
final class FakeSockets implements ISocketProvider, AutoCloseable {

    /** Where a message from the client goes, with the way to answer on its connection. */
    @FunctionalInterface
    interface Receiver {
        void receive(SapientMessage message, Consumer<SapientMessage> reply);
    }

    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();

    private final Receiver receiver;
    private final Runnable unreadable;
    private final KeepalivePrefixes keepalive; // null when the keepalive is out of band
    private final Count connects;
    private final Count disconnects;
    private final ProbeListener probe;
    private final AtomicReference<Connection> current = new AtomicReference<>();
    private final AtomicReference<Held> nextWriteHeld = new AtomicReference<>();
    private final AtomicBoolean refuseNext = new AtomicBoolean();

    FakeSockets(
            Receiver receiver,
            Runnable unreadable,
            KeepalivePrefixes keepalive,
            Count connects,
            Count disconnects) {
        this.receiver = receiver;
        this.unreadable = unreadable;
        this.keepalive = keepalive;
        this.connects = connects;
        this.disconnects = disconnects;
        this.probe = new ProbeListener();
    }

    @Override
    public Socket get() throws IOException {
        if (!probe.reachable() || refuseNext.getAndSet(false)) {
            throw new ConnectException("Connection refused");
        }
        Connection connection = new Connection();
        current.set(connection);
        connects.increment();
        return connection.socket;
    }

    @Override
    public String host() {
        return LOOPBACK.getHostAddress();
    }

    @Override
    public int port() {
        return probe.port;
    }

    void send(SapientMessage message) {
        live().send(message);
    }

    void sendRawPrefix(int prefix) {
        live().sendRawPrefix(prefix);
    }

    void drop() {
        live().drop();
        current.set(null);
    }

    Held stopReading() {
        Held held = new Held();
        nextWriteHeld.set(held);
        return held;
    }

    void refuseNextConnection() {
        refuseNext.set(true);
    }

    void goUnreachable() {
        probe.goUnreachable();
    }

    void goReachable() {
        probe.goReachable();
    }

    @Override
    public void close() {
        probe.close();
    }

    private Connection live() {
        Connection connection = current.get();
        if (connection == null || connection.closed.get()) {
            throw new IllegalStateException(
                    "no open connection; wait for the client's next one with awaitConnects");
        }
        return connection;
    }

    private static byte[] prefix(int value) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
    }

    /** One socket: a mocked {@link Socket} over two in-memory streams. */
    private final class Connection {
        private final Socket socket = mock(Socket.class);
        private final ToClient toClient = new ToClient();
        private final FromClient fromClient = new FromClient();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicReference<Held> stuckWrite = new AtomicReference<>();

        Connection() throws IOException {
            Answer<Void> close =
                    call -> {
                        closedByClient();
                        return null;
                    };
            when(socket.getInputStream()).thenReturn(toClient);
            when(socket.getOutputStream()).thenReturn(fromClient);
            when(socket.isConnected()).thenReturn(true);
            when(socket.isClosed()).thenAnswer(call -> closed.get());
            doAnswer(close).when(socket).close();
        }

        void send(SapientMessage message) {
            byte[] body = message.toByteArray();
            toClient.write(
                    ByteBuffer.allocate(4 + body.length)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .putInt(body.length)
                            .put(body)
                            .array());
        }

        void sendRawPrefix(int value) {
            toClient.write(prefix(value));
        }

        /**
         * The fusion node ends its side: the client reads EOF, and what it still writes arrives.
         */
        void drop() {
            toClient.end();
        }

        /** Counts every call, so a test can tell a socket closed twice. */
        private void closedByClient() {
            closed.set(true);
            disconnects.increment();
            toClient.end();
            Held stuck = stuckWrite.get();
            if (stuck != null) {
                stuck.release();
            }
        }

        /** What the client writes. Each whole frame goes to the fusion node as it completes. */
        private final class FromClient extends OutputStream {
            private final ByteArrayOutputStream unread = new ByteArrayOutputStream();

            @Override
            public void write(int b) throws IOException {
                write(new byte[] {(byte) b}, 0, 1);
            }

            /**
             * Takes the bytes in. After {@link FakeSockets#stopReading()} the next write stops
             * halfway, the way a write does once the peer stops reading and the send buffer fills.
             * Closing the socket fails the stuck write. Interrupting it closes the socket first, as
             * the JDK does to a virtual thread interrupted inside a socket write.
             */
            @Override
            public void write(byte[] bytes, int offset, int length) throws IOException {
                failIfClosed();
                Held held = nextWriteHeld.getAndSet(null);
                if (held == null) {
                    take(bytes, offset, length);
                    return;
                }
                int half = length / 2;
                take(bytes, offset, half);
                stuckWrite.set(held);
                try {
                    failIfClosed();
                    held.enterInterruptibly();
                } catch (InterruptedException e) {
                    closedByClient();
                    Thread.currentThread().interrupt();
                    throw new SocketException("Closed by interrupt");
                } finally {
                    stuckWrite.set(null);
                }
                failIfClosed();
                take(bytes, offset + half, length - half);
            }

            private void failIfClosed() throws SocketException {
                if (closed.get()) {
                    throw new SocketException("Socket closed");
                }
            }

            /** Adds bytes to what the fusion node has read, and handles each complete frame. */
            private synchronized void take(byte[] bytes, int offset, int length) {
                unread.write(bytes, offset, length);
                ByteBuffer frames =
                        ByteBuffer.wrap(unread.toByteArray()).order(ByteOrder.LITTLE_ENDIAN);
                while (frames.remaining() >= 4) {
                    int start = frames.position();
                    int size = frames.getInt();
                    if (keepalive != null && size == keepalive.ping()) {
                        sendRawPrefix(keepalive.pong());
                    } else if (size < 0) {
                        unreadable.run(); // nothing after a broken length can be read either
                        frames.position(frames.limit());
                    } else if (frames.remaining() < size) {
                        frames.position(start);
                        break;
                    } else {
                        byte[] body = new byte[size];
                        frames.get(body);
                        deliver(body);
                    }
                }
                byte[] rest = new byte[frames.remaining()];
                frames.get(rest);
                unread.reset();
                unread.writeBytes(rest);
            }

            private void deliver(byte[] body) {
                SapientMessage message;
                try {
                    message = SapientMessage.parseFrom(body);
                } catch (InvalidProtocolBufferException e) {
                    unreadable.run();
                    return;
                }
                receiver.receive(message, Connection.this::send);
            }
        }
    }

    /** What the client reads: everything the fusion node sent, then EOF for good once it ends. */
    private static final class ToClient extends InputStream {
        private static final byte[] END = new byte[0];
        private final BlockingQueue<byte[]> chunks = new LinkedBlockingQueue<>();
        private byte[] chunk = new byte[0];
        private int position;
        private boolean ended; // read only by the one reading thread

        void write(byte[] bytes) {
            chunks.add(bytes);
        }

        void end() {
            chunks.add(END);
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            if (ended) {
                return -1;
            }
            if (position == chunk.length) {
                chunk = next();
                position = 0;
                if (chunk == END) {
                    ended = true;
                    return -1;
                }
            }
            int count = Math.min(length, chunk.length - position);
            System.arraycopy(chunk, position, buffer, offset, count);
            position += count;
            return count;
        }

        private byte[] next() throws InterruptedIOException {
            try {
                return chunks.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("read interrupted");
            }
        }
    }

    /**
     * A real listener on the loopback address that accepts probes and closes them. While
     * unreachable it keeps the port bound without listening, so a connect is refused and no other
     * test can take the port in the meantime. The port is never free: with {@code SO_REUSEPORT} the
     * reservation binds before the listener closes, and the listener before the reservation closes.
     * A free port could become another connection's source port at any moment.
     */
    private static final class ProbeListener {
        private final int port;
        private Listening listening; // null while unreachable
        private Socket reservation; // holds the port while unreachable

        ProbeListener() {
            listening = Listening.on(0);
            port = listening.socket.getLocalPort();
        }

        synchronized boolean reachable() {
            return listening != null;
        }

        synchronized void goUnreachable() {
            if (listening == null) {
                return;
            }
            reservation = reserve(port);
            listening.stop();
            listening = null;
        }

        synchronized void goReachable() {
            if (listening != null) {
                return;
            }
            listening = Listening.on(port);
            closeReservation();
        }

        synchronized void close() {
            if (listening != null) {
                listening.stop();
            }
            closeReservation();
        }

        private static Socket reserve(int port) {
            try {
                Socket socket = new Socket();
                socket.setReuseAddress(true);
                if (socket.supportedOptions().contains(StandardSocketOptions.SO_REUSEPORT)) {
                    socket.setOption(StandardSocketOptions.SO_REUSEPORT, true);
                }
                socket.bind(new InetSocketAddress(LOOPBACK, port));
                return socket;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private void closeReservation() {
            if (reservation == null) {
                return;
            }
            try {
                reservation.close();
                reservation = null;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /** A listening socket and the thread that accepts on it. */
    private record Listening(ServerSocket socket, Thread acceptor) {

        static Listening on(int port) {
            try {
                ServerSocket socket = new ServerSocket();
                socket.setReuseAddress(true);
                if (socket.supportedOptions().contains(StandardSocketOptions.SO_REUSEPORT)) {
                    socket.setOption(StandardSocketOptions.SO_REUSEPORT, true);
                }
                socket.bind(new InetSocketAddress(LOOPBACK, port));
                Thread acceptor =
                        Thread.ofVirtual()
                                .name("fake-fusion-node-probes")
                                .start(() -> acceptProbes(socket));
                return new Listening(socket, acceptor);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        /**
         * Closes the socket and waits for the acceptor. The port is free only after that: a close
         * while a thread sits in {@code accept()} finishes when that thread leaves it.
         */
        void stop() {
            try {
                socket.close();
                acceptor.join();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("still waiting for the probe listener to close", e);
            }
        }

        /** A probe only connects, so there is nothing to say: accept and close. */
        private static void acceptProbes(ServerSocket socket) {
            while (!socket.isClosed()) {
                try {
                    socket.accept().close();
                } catch (IOException e) {
                    // the socket closed: the fusion node went unreachable, or the test is over
                }
            }
        }
    }
}
