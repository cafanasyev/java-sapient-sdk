package io.sapient.testing;

/** A {@link Runnable} that may throw, for calls such as {@code publish} that declare exceptions. */
@FunctionalInterface
public interface ThrowingRunnable {
    void run() throws Exception;
}
