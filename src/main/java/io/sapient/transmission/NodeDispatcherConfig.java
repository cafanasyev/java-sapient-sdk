package io.sapient.transmission;

import java.time.Duration;
import java.util.UUID;
import lombok.NonNull;

/**
 * Configuration for {@link NodeDispatcher}.
 *
 * @param onlineCheckInterval interval between node online-status polls
 * @param publishTimeout timeout for publish operations
 * @param registrationAckTimeout timeout for waiting for a {@code RegistrationAck} after sending a
 *     {@code Registration}
 * @param reconnectGracePeriod how long the server retains a registration after closing the TCP
 *     connection due to missed status reports (BSI Flex 335 v2.0 §4.9)
 * @param destinationId the fusion node recipient for all outbound messages
 * @param registrationJitterWindow uniform-random delay window applied before each registration
 *     message to spread the registration storm that occurs when many clients reconnect together
 *     (CHANGELOG §5). Tests should set this to {@link Duration#ZERO} to make the registration
 *     publish deterministic.
 */
public record NodeDispatcherConfig(
        @NonNull Duration onlineCheckInterval,
        @NonNull Duration publishTimeout,
        @NonNull Duration registrationAckTimeout,
        @NonNull Duration reconnectGracePeriod,
        @NonNull UUID destinationId,
        @NonNull Duration registrationJitterWindow) {

    private static final Duration DEFAULT_ONLINE_CHECK_INTERVAL = Duration.ofSeconds(5);
    private static final Duration DEFAULT_PUBLISH_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_REGISTRATION_ACK_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_RECONNECT_GRACE_PERIOD = Duration.ofMinutes(2);

    /**
     * Returns a configuration with default values (5s polling, 5s publish timeout, 5s registration
     * ack timeout, 2 min reconnect grace period).
     *
     * <p>The connection-loss detection delay is not here any more. It belongs to the transport,
     * because only the transport knows how liveness is checked, and the dispatcher reads it from
     * the {@code IClient} it was given.
     *
     * @param destinationId the fusion node recipient for all outbound messages
     * @return configuration with default intervals
     */
    public static NodeDispatcherConfig defaults(@NonNull UUID destinationId) {
        return new NodeDispatcherConfig(
                DEFAULT_ONLINE_CHECK_INTERVAL,
                DEFAULT_PUBLISH_TIMEOUT,
                DEFAULT_REGISTRATION_ACK_TIMEOUT,
                DEFAULT_RECONNECT_GRACE_PERIOD,
                destinationId,
                Jitter.REGISTRATION_JITTER_WINDOW);
    }
}
