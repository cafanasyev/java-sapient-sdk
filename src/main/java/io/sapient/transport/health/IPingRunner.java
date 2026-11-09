package io.sapient.transport.health;

import java.time.Duration;

/** Runs one ICMP echo. Behind an interface so tests do not have to spawn a process. */
@FunctionalInterface
public interface IPingRunner {

    /**
     * Sends one ICMP echo request and waits for the reply.
     *
     * @param host hostname or IP address to ping
     * @param timeout how long to wait for the reply
     * @return {@code true} if the host answered in time
     */
    boolean ping(String host, Duration timeout);
}
