package io.sapient.testing;

import com.google.protobuf.Message;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * What one side of a test has received, in order, as short lines such as {@code "n1 status OK
 * new"}. Reading blocks until the next line arrives; there is no timeout here, the test-wide
 * timeout is the only one. An interrupted wait fails with what it waited for and every line so far.
 */
public final class Conversation<T extends Message> {

    private record Entry<T>(String line, T message) {}

    private final BlockingQueue<Entry<T>> unread = new LinkedBlockingQueue<>();
    private final List<String> lines = new ArrayList<>();

    synchronized void add(String line, T message) {
        lines.add(line);
        unread.add(new Entry<>(line, message));
    }

    /** Reads the next lines and fails at the first one that differs. */
    public void expect(String... expected) {
        for (String line : expected) {
            String actual = take(line).line();
            if (!actual.equals(line)) {
                throw failure("expected <%s> but got <%s>".formatted(line, actual));
            }
        }
    }

    /** Skips lines until it reads {@code line}, and returns the message behind it. */
    public T await(String line) {
        Entry<T> entry = take(line);
        while (!entry.line().equals(line)) {
            entry = take(line);
        }
        return entry.message();
    }

    /** Reads the next line and returns the message behind it. */
    public T next() {
        return take("the next message").message();
    }

    /** Fails if a line is waiting to be read. Only meaningful after a barrier, such as close(). */
    public void expectNothingMore() {
        Entry<T> next = unread.peek();
        if (next != null) {
            throw failure("expected nothing more but got <%s>".formatted(next.line()));
        }
    }

    /** Every line received so far, read or not. */
    public synchronized List<String> lines() {
        return List.copyOf(lines);
    }

    private Entry<T> take(String awaited) {
        try {
            return unread.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failure("still waiting for <%s>".formatted(awaited));
        }
    }

    private AssertionError failure(String message) {
        return new AssertionError(message + "; conversation: " + lines());
    }
}
