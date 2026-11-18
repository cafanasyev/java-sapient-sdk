package io.sapient.testing;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

@Execution(ExecutionMode.CONCURRENT)
class HeldTest {

    private final Held held = new Held();

    @Test
    void holdsTheCallUntilReleased() throws Exception {
        var call = Async.run(held::enter);
        held.awaitArrival();
        call.awaitParkedOrDone();
        assertFalse(call.isDone(), "the call must wait for release()");

        held.release();
        call.join();
    }

    @Test
    void hasArrivedTellsWhetherTheCallStarted() throws Exception {
        assertFalse(held.hasArrived());

        var call = Async.run(held::enter);
        held.awaitArrival();

        assertTrue(held.hasArrived());
        held.release();
        call.join();
    }

    @Test
    void interruptDoesNotEndTheHoldButIsNoticed() throws Exception {
        var call =
                Async.run(
                        () -> {
                            held.enter();
                            assertTrue(Thread.currentThread().isInterrupted(), "flag restored");
                        });
        held.awaitArrival();

        call.interrupt();
        held.awaitInterrupt();
        call.awaitParkedOrDone();
        assertFalse(call.isDone(), "an interrupt must not end the hold");

        held.release();
        call.join();
    }

    @Test
    void releaseBeforeArrivalLetsTheCallThrough() throws Exception {
        held.release();
        held.release();

        Async.run(held::enter).join();
        held.awaitArrival();
    }
}
