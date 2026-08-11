package io.sapient.transport.health;

import java.time.Duration;
import lombok.NonNull;

/**
 * Checks the host with one ICMP echo. Use it only where ICMP is allowed between the node and the
 * fusion server. It proves the host is up, not that the SAPIENT port still accepts connections.
 */
public class IcmpHealthCheck implements IHealthCheck {

    private final String host;
    private final Duration timeout;
    private final IPingRunner runner;

    public IcmpHealthCheck(@NonNull String host, @NonNull Duration timeout) {
        this(host, timeout, new SystemPingRunner());
    }

    IcmpHealthCheck(@NonNull String host, @NonNull Duration timeout, @NonNull IPingRunner runner) {
        this.host = host;
        this.timeout = timeout;
        this.runner = runner;
    }

    @Override
    public boolean check() {
        return runner.ping(host, timeout);
    }
}
