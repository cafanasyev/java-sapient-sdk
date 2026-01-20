package io.sapient.client;


import lombok.Getter;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.function.Consumer;

public class TCPClient implements IClient {
    @Getter
    private final String host;
    @Getter
    private final int port;
    private final SocketChannel ch;
    private final Selector selector;
    private final ByteBuffer in = ByteBuffer.allocateDirect(64 * 1024);
    private Consumer<ByteBuffer> consumer = b -> {};

    public TCPClient(String host, int port) throws IOException {
        this.host = host;
        this.port = port;

        ch = SocketChannel.open();
        ch.configureBlocking(false);
        selector = Selector.open();
    }

    @Override
    public void run() throws IOException {
        ch.connect(new InetSocketAddress(host, port));
        ch.register(selector, SelectionKey.OP_CONNECT);

        loop();
    }

    @Override
    public void close() throws IOException {
        selector.wakeup();
        ch.close();
        selector.close();
    }

    @Override
    public void write(ByteBuffer msg) throws IOException {
        while (msg.hasRemaining()) ch.write(msg);
    }

    @Override
    public void subscribe(Consumer<ByteBuffer> c) {
        this.consumer = c;
    }

    private void loop() throws IOException {
        while (ch.isOpen()) {
            selector.select();

            for (Iterator<SelectionKey> it = selector.selectedKeys().iterator(); it.hasNext();) {
                SelectionKey k = it.next(); {
                    it.remove();
                }

                if (k.isConnectable()) {
                    ch.finishConnect();
                    k.interestOps(SelectionKey.OP_READ);
                }

                if (k.isReadable()) {
                    if (ch.read(in) <= 0) return;
                    in.flip();
                    consumer.accept(in.asReadOnlyBuffer());
                    in.compact();
                }
            }
        }
    }
}
