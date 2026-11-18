package io.sapient.transport.health;

/** One way to check that the peer is still alive. */
public interface IHealthCheck {

    /**
     * Runs one check. Blocks for at most the configured timeout.
     *
     * @return {@code true} if the peer proved it is alive
     */
    boolean check();

    /**
     * Told by the read path that a frame arrived from the peer. Only the in-band checks care: an
     * inbound frame is what answers their ping. The out-of-band checks ignore it, because the
     * monitor already tracks inbound traffic on its own.
     */
    default void onInbound() {}
}
