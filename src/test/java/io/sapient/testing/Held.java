package io.sapient.testing;

import java.util.concurrent.CountDownLatch;

/**
 * A call stopped at a known point until the test releases it. The fakes hand one out from {@code
 * holdNext}; the held call runs {@link #enter()} and waits there.
 *
 * <p>A held call ignores an interrupt, the way blocking I/O does, but notices it: {@link
 * #awaitInterrupt()} returns once it arrives, and the call returns with the interrupt flag set.
 */
public final class Held {

    private final CountDownLatch arrived = new CountDownLatch(1);
    private final CountDownLatch interrupted = new CountDownLatch(1);
    private final CountDownLatch released = new CountDownLatch(1);

    /** Blocks until the held call has started. */
    public void awaitArrival() {
        await(arrived, "the held call to arrive");
    }

    /** Whether the held call has started, for a test that cannot be sure the call happened. */
    public boolean hasArrived() {
        return arrived.getCount() == 0;
    }

    /** Blocks until the held call's thread has been interrupted. */
    public void awaitInterrupt() {
        await(interrupted, "the held call to be interrupted");
    }

    /** Lets the held call continue. A release before the call arrives lets it pass straight by. */
    public void release() {
        released.countDown();
    }

    /** Runs on the held thread: waits for {@link #release()}, noticing interrupts on the way. */
    void enter() {
        arrived.countDown();
        boolean wasInterrupted = false;
        while (true) {
            try {
                released.await();
                break;
            } catch (InterruptedException e) {
                wasInterrupted = true;
                interrupted.countDown();
            }
        }
        if (wasInterrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Runs on the held thread like {@link #enter()}, but an interrupt ends the wait: it throws,
     * once {@link #awaitInterrupt()} can see it. For a call that an interrupt breaks, such as a
     * socket write on a virtual thread.
     */
    void enterInterruptibly() throws InterruptedException {
        arrived.countDown();
        try {
            released.await();
        } catch (InterruptedException e) {
            interrupted.countDown();
            throw e;
        }
    }

    private static void await(CountDownLatch latch, String what) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("still waiting for " + what, e);
        }
    }
}
