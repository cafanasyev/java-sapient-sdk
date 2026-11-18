package io.sapient.testing;

/** A counter a test can wait on, such as the connects a fake has seen. */
final class Count {

    private final String name;
    private int value;

    Count(String name) {
        this.name = name;
    }

    synchronized void increment() {
        value++;
        notifyAll();
    }

    synchronized int get() {
        return value;
    }

    /** Blocks until the count reaches {@code count}. */
    synchronized void await(int count) {
        while (value < count) {
            try {
                wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(
                        "still waiting for " + count + " " + name + ", got " + value, e);
            }
        }
    }
}
