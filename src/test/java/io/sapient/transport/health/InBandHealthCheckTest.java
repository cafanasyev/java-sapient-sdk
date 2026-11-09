package io.sapient.transport.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class InBandHealthCheckTest {

    private static final Duration TIMEOUT = Duration.ofMillis(300);

    @Test
    @Timeout(10)
    void testAliveWhenAnswerArrives() throws Exception {
        // the lambda needs the object it is passed into, so hold it in a one-slot array
        var self = new InBandHealthCheck[1];
        self[0] =
                new InBandHealthCheck(
                        0x00000000,
                        TIMEOUT,
                        (prefix, t) -> {
                            // the read path sees the pong shortly after the ping goes out
                            Thread.ofVirtual()
                                    .start(
                                            () -> {
                                                sleepQuietly(50);
                                                self[0].onInbound();
                                            });
                            return true;
                        });

        assertTrue(self[0].check());
    }

    @Test
    @Timeout(10)
    void testDeadWhenNoAnswerArrives() {
        var check = new InBandHealthCheck(0x00000000, TIMEOUT, (prefix, t) -> true);
        assertFalse(check.check());
    }

    @Test
    @Timeout(10)
    void testSendsTheConfiguredPingPrefix() {
        var sent = new AtomicInteger(Integer.MIN_VALUE);
        var check =
                new InBandHealthCheck(
                        0x00000000,
                        TIMEOUT,
                        (prefix, t) -> {
                            sent.set(prefix);
                            return true;
                        });

        check.check();

        assertEquals(0x00000000, sent.get());
    }

    @Test
    @Timeout(10)
    void testBlockedWritePathIsAFailure() {
        // writer could not take the write slot for the whole timeout, and the peer sent
        // us nothing meanwhile → the link is not healthy
        var check = new InBandHealthCheck(0x00000000, TIMEOUT, (prefix, t) -> false);
        assertFalse(check.check());
    }

    @Test
    @Timeout(10)
    void testBlockedWritePathIsAliveIfThePeerSpokeAnyway() {
        // the lambda needs the object it is passed into, so hold it in a one-slot array
        var self = new InBandHealthCheck[1];
        self[0] =
                new InBandHealthCheck(
                        0x00000000,
                        TIMEOUT,
                        (prefix, t) -> {
                            // a frame arrives from the peer while we are queued for the slot
                            self[0].onInbound();
                            return false;
                        });

        assertTrue(self[0].check());
    }

    @Test
    @Timeout(10)
    void testAnswerFromBeforeTheCheckDoesNotCount() {
        // the monitor checks only a full interval after the last inbound frame, so a frame
        // that is already waiting when the check starts is at least that old
        var check = new InBandHealthCheck(0x00000000, TIMEOUT, (prefix, t) -> true);

        check.onInbound();

        assertFalse(check.check());
    }

    @Test
    @Timeout(10)
    void testSlowWriteAndTheAnswerShareOneTimeout() {
        // the write takes almost the whole timeout, so the wait for the answer gets only
        // what is left of it
        var check =
                new InBandHealthCheck(
                        0x00000000,
                        TIMEOUT,
                        (prefix, t) -> {
                            sleepQuietly(TIMEOUT.toMillis() - 20);
                            return true;
                        });

        long started = System.nanoTime();
        assertFalse(check.check());
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertTrue(
                elapsedMillis < TIMEOUT.toMillis() * 3 / 2,
                "the check took " + elapsedMillis + " ms");
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
