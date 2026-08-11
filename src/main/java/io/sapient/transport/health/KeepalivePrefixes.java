package io.sapient.transport.health;

/**
 * The two length-prefix values that carry a keepalive instead of a message. Resolved once per
 * connection, so the read loop compares two ints and never looks at the type again.
 *
 * <p>Same table as the SAPIENT server. {@code ECHO} and {@code PINGPONG} both use 0 as the ping but
 * answer differently, so client and server must run the same type.
 *
 * @param ping prefix the client sends to ask "are you there"
 * @param pong prefix the peer sends back
 */
public record KeepalivePrefixes(int ping, int pong) {

    /**
     * Resolves a type into its prefixes.
     *
     * @param type the health check type
     * @return the prefixes, or {@code null} for a type that puts nothing on the wire
     * @throws IllegalArgumentException if the type has no wire format at this layer
     */
    public static KeepalivePrefixes of(HealthCheckType type) {
        return switch (type) {
            // out of band: nothing on the wire, so the read loop keeps its guard intact
            case NETCAT, ICMP -> null;
            case ECHO -> new KeepalivePrefixes(0x00000000, 0x00000000);
            case PINGPONG -> new KeepalivePrefixes(0x00000000, 0xFFFFFFFF);
            case TRANSPORT_NATIVE ->
                    throw new IllegalArgumentException(
                            "TRANSPORT_NATIVE has no length-prefix format; it belongs to the"
                                    + " transport, not to the framing layer");
        };
    }
}
