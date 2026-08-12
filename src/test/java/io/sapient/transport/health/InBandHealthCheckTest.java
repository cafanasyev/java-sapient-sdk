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
        var check = new InBandHealthCheck(0x00000000, TIMEOUT, (prefix, t) -> true);

        // the read path sees the pong shortly after the ping goes out
        Thread.ofVirtual()
                .start(
                        () -> {
                            sleepQuietly(50);
                            check.onInbound();
                        });

        assertTrue(check.check());
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
    void testAnswerFromAPreviousCheckDoesNotCountTwice() {
        var check = new InBandHealthCheck(0x00000000, TIMEOUT, (prefix, t) -> true);

        check.onInbound();
        assertTrue(check.check(), "the pending answer satisfies this check");

        // nothing new arrived, so the next check must not reuse the old answer
        assertFalse(check.check());
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
