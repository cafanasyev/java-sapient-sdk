package io.sapient.transport.health;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class HealthMonitorTest {

    private static final Duration INTERVAL = Duration.ofMillis(100);
    private static final Duration TIMEOUT = Duration.ofMillis(50);

    private static HealthCheckConfig config(int threshold) {
        return new HealthCheckConfig(HealthCheckType.NETCAT, INTERVAL, TIMEOUT, threshold);
    }

    /** A check whose verdict the test controls, counting how many times it ran. */
    private static class FakeCheck implements IHealthCheck {
        final AtomicBoolean alive = new AtomicBoolean(true);
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public boolean check() {
            calls.incrementAndGet();
            return alive.get();
        }
    }

    @Test
    @Timeout(10)
    void testDoesNotFireBeforeTheFirstInterval() throws Exception {
        var check = new FakeCheck();
        var monitor = new HealthMonitor(check, config(3), () -> {});
        Thread t = Thread.ofVirtual().start(monitor);

        Thread.sleep(50);
        assertEquals(0, check.calls.get(), "first check must wait one full interval");

        t.interrupt();
    }

    @Test
    @Timeout(10)
    void testDeclaresDeadOnlyAfterThresholdConsecutiveFailures() throws Exception {
        var check = new FakeCheck();
        check.alive.set(false);
        var dead = new CountDownLatch(1);
        var monitor = new HealthMonitor(check, config(3), dead::countDown);
        Thread t = Thread.ofVirtual().start(monitor);

        // two failures are not enough: 2 × 100ms interval + 50ms timeout ≈ 250ms
        Thread.sleep(260);
        assertEquals(1, dead.getCount(), "must not give up before the third failure");

        assertTrue(dead.await(3, SECONDS), "third failure must declare the link dead");
        assertEquals(3, check.calls.get());

        t.interrupt();
    }

    @Test
    @Timeout(10)
    void testOneSuccessResetsTheFailureCount() throws Exception {
        var check = new FakeCheck();
        var dead = new AtomicBoolean(false);
        var monitor = new HealthMonitor(check, config(3), () -> dead.set(true));
        Thread t = Thread.ofVirtual().start(monitor);

        // fail, fail, pass, fail, fail — never three in a row
        for (int i = 0; i < 2; i++) {
            check.alive.set(false);
            Thread.sleep(210);
            check.alive.set(true);
            Thread.sleep(210);
        }

        assertFalse(dead.get(), "a success in between must reset the count");

        t.interrupt();
    }

    @Test
    @Timeout(10)
    void testChecksAreOneIntervalApartEvenAfterAFailure() throws Exception {
        var check = new FakeCheck();
        check.alive.set(false);
        var monitor = new HealthMonitor(check, config(100), () -> {});
        Thread t = Thread.ofVirtual().start(monitor);

        // 500ms at a 100ms cadence is about 5 checks. Back-to-back retries would give
        // roughly 10 (each failed check costs only the 50ms timeout).
        Thread.sleep(520);
        int calls = check.calls.get();
        assertTrue(calls <= 7, "expected about 5 checks, got " + calls);

        t.interrupt();
    }

    @Test
    @Timeout(10)
    void testInboundTrafficDelaysTheNextCheck() throws Exception {
        var check = new FakeCheck();
        var monitor = new HealthMonitor(check, config(3), () -> {});
        Thread t = Thread.ofVirtual().start(monitor);

        // a frame every 40ms on a 100ms interval means the check never comes due
        for (int i = 0; i < 12; i++) {
            monitor.onInbound();
            Thread.sleep(40);
        }

        assertEquals(0, check.calls.get(), "steady inbound traffic must keep checks away");

        t.interrupt();
    }

    @Test
    @Timeout(10)
    void testOnInboundIsForwardedToTheCheck() {
        var forwarded = new AtomicBoolean(false);
        IHealthCheck check =
                new IHealthCheck() {
                    @Override
                    public boolean check() {
                        return true;
                    }

                    @Override
                    public void onInbound() {
                        forwarded.set(true);
                    }
                };

        new HealthMonitor(check, config(3), () -> {}).onInbound();

        assertTrue(forwarded.get(), "the in-band check needs the inbound signal");
    }
}
