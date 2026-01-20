package io.sapient.client;

import javax.net.ssl.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.function.Consumer;

public class TLSClient implements IClient {

    private final TCPClient tcp;
    private final SSLEngine ssl;

    private final ByteBuffer netIn;
    private final ByteBuffer netOut;
    private final ByteBuffer appIn;
    private Consumer<ByteBuffer> consumer = b -> {};

    public TLSClient(TCPClient tcp, SSLContext ctx) {
        this.tcp = tcp;

        ssl = ctx.createSSLEngine(tcp.getHost(), tcp.getPort());
        ssl.setUseClientMode(true);

        SSLSession session = ssl.getSession();
        netIn  = ByteBuffer.allocate(session.getPacketBufferSize());
        netOut = ByteBuffer.allocate(session.getPacketBufferSize());
        appIn  = ByteBuffer.allocate(session.getApplicationBufferSize());
    }

    @Override
    public void run() throws IOException {
        ssl.beginHandshake();
        tcp.subscribe(this::onEncrypted);
        tcp.run();
    }

    private void onEncrypted(ByteBuffer encrypted) {
        try {
            netIn.put(encrypted);
            netIn.flip();

            while (netIn.hasRemaining()) {
                ssl.unwrap(netIn, appIn);
            }

            netIn.compact();

            appIn.flip();
            consumer.accept(appIn.asReadOnlyBuffer());
            appIn.clear();

            handshake();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void handshake() throws IOException {
        if (ssl.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING)
            return;

        netOut.clear();

        ssl.wrap(ByteBuffer.allocate(0), netOut);
        netOut.flip();
        tcp.write(netOut);
    }

    @Override
    public void write(ByteBuffer message) throws IOException {
        while (message.hasRemaining()) {
            netOut.clear();
            ssl.wrap(message, netOut);
            netOut.flip();
            tcp.write(netOut);
        }
    }

    @Override
    public void subscribe(Consumer<ByteBuffer> c) {
        this.consumer = c;
    }

    @Override
    public void close() throws IOException {
        tcp.close();
    }
}
