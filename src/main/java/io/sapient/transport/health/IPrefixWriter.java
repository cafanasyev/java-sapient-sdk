package io.sapient.transport.health;

import java.time.Duration;

/** Writes a bare 4-byte length prefix on the live connection. Implemented by the client. */
@FunctionalInterface
public interface IPrefixWriter {

    /**
     * Writes the prefix, waiting up to {@code timeout} for exclusive access to the write path.
     *
     * <p>It never interrupts a write already in progress — it queues like any publisher.
     *
     * @param prefix the 4-byte little-endian value to write
     * @param timeout how long to wait for the write path
     * @return {@code true} if the prefix was written and flushed
     */
    boolean writePrefix(int prefix, Duration timeout);
}
