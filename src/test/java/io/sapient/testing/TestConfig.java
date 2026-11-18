package io.sapient.testing;

import io.sapient.transmission.NodeDispatcherConfig;
import java.time.Duration;
import java.util.UUID;

/**
 * The dispatcher config tests run with: online checks every 10 ms, a 5 s publish timeout, a 5 s
 * registration ack timeout, a 2 min reconnect grace period and no registration jitter. The ack
 * timeout stays long so that a test answering a registration by hand is never raced by a retry.
 */
public final class TestConfig {

    /** The fusion node every message is addressed to. */
    public static final UUID FUSION_NODE_ID =
            UUID.fromString("00000000-0000-0000-0000-00000000f00d");

    private TestConfig() {}

    public static NodeDispatcherConfig defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Duration onlineCheckInterval = Duration.ofMillis(10);
        private Duration publishTimeout = Duration.ofSeconds(5);
        private Duration registrationAckTimeout = Duration.ofSeconds(5);
        private Duration reconnectGracePeriod = Duration.ofMinutes(2);
        private Duration registrationJitterWindow = Duration.ZERO;

        private Builder() {}

        public Builder withOnlineCheckInterval(Duration interval) {
            onlineCheckInterval = interval;
            return this;
        }

        public Builder withPublishTimeout(Duration timeout) {
            publishTimeout = timeout;
            return this;
        }

        public Builder withRegistrationAckTimeout(Duration timeout) {
            registrationAckTimeout = timeout;
            return this;
        }

        public Builder withReconnectGracePeriod(Duration period) {
            reconnectGracePeriod = period;
            return this;
        }

        public Builder withRegistrationJitterWindow(Duration window) {
            registrationJitterWindow = window;
            return this;
        }

        public NodeDispatcherConfig build() {
            return new NodeDispatcherConfig(
                    onlineCheckInterval,
                    publishTimeout,
                    registrationAckTimeout,
                    reconnectGracePeriod,
                    FUSION_NODE_ID,
                    registrationJitterWindow);
        }
    }
}
