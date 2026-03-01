package io.sapient.transmission;

import java.time.Duration;
import java.util.UUID;
import lombok.NonNull;

/**
 * Configuration for {@link NodeDispatcher}.
 *
 * @param onlineCheckInterval interval between node online-status polls
 * @param publishTimeout timeout for publish operations
 * @param destinationId the fusion node recipient for all outbound messages
 */
public record NodeDispatcherConfig(
        @NonNull Duration onlineCheckInterval,
        @NonNull Duration publishTimeout,
        @NonNull UUID destinationId) {

    private static final Duration DEFAULT_ONLINE_CHECK_INTERVAL = Duration.ofSeconds(5);
    private static final Duration DEFAULT_PUBLISH_TIMEOUT = Duration.ofSeconds(5);

    /**
     * Returns a configuration with default values (5s polling, 5s publish timeout).
     *
     * @param destinationId the fusion node recipient for all outbound messages
     * @return configuration with default intervals
     */
    public static NodeDispatcherConfig defaults(@NonNull UUID destinationId) {
        return new NodeDispatcherConfig(
                DEFAULT_ONLINE_CHECK_INTERVAL, DEFAULT_PUBLISH_TIMEOUT, destinationId);
    }
}
