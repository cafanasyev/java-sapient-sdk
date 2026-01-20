package io.sapient.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.function.Consumer;

public interface IClient extends AutoCloseable {
    void run() throws IOException;
    void write(ByteBuffer message) throws IOException;
    void subscribe(Consumer<ByteBuffer> c) throws IOException;
}
