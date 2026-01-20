package io.sapient.transmission;

import java.time.Duration;

/**
 * Configuration for {@link NodeDispatcher}.
 *
 * @param onlineCheckInterval interval between node online-status polls
 * @param publishTimeout timeout for publish operations
 */
public record NodeDispatcherConfig(Duration onlineCheckInterval, Duration publishTimeout) {

    private static final Duration DEFAULT_ONLINE_CHECK_INTERVAL = Duration.ofSeconds(5);
    private static final Duration DEFAULT_PUBLISH_TIMEOUT = Duration.ofSeconds(5);

    /**
     * Returns a configuration with default values (5s polling, 5s publish timeout).
     *
     * @return default configuration
     */
    public static NodeDispatcherConfig defaults() {
        return new NodeDispatcherConfig(DEFAULT_ONLINE_CHECK_INTERVAL, DEFAULT_PUBLISH_TIMEOUT);
    }
}
