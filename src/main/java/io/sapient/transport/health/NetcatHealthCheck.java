package io.sapient.transport.health;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import lombok.NonNull;

/**
 * Opens a throwaway TCP connection and closes it at once, like {@code nc -z host port}. Never
 * touches the managed connection, so it never competes with a publisher.
 *
 * <p>It proves the port still accepts connections. It does not prove the managed connection is
 * healthy — a half-open socket can survive while the listener answers fresh connects.
 */
public class NetcatHealthCheck implements IHealthCheck {

    private final String host;
    private final int port;
    private final Duration timeout;

    public NetcatHealthCheck(@NonNull String host, int port, @NonNull Duration timeout) {
        this.host = host;
        this.port = port;
        this.timeout = timeout;
    }

    @Override
    public boolean check() {
        try (Socket probe = new Socket()) {
            probe.connect(new InetSocketAddress(host, port), (int) timeout.toMillis());
            return true;
        } catch (IOException | IllegalArgumentException e) {
            return false;
        }
    }
}
