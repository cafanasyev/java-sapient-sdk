package io.sapient.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

@Execution(ExecutionMode.CONCURRENT)
class AsyncTest {

    @Test
    void joinRethrowsTheFailure() {
        var call =
                Async.run(
                        () -> {
                            throw new IOException("boom");
                        });

        assertThrows(IOException.class, call::join);
    }

    @Test
    void joinFailureReturnsTheException() {
        var call =
                Async.run(
                        () -> {
                            throw new IOException("boom");
                        });

        assertEquals("boom", call.joinFailure().getMessage());
    }

    @Test
    void joinFailureFailsWhenTheCallSucceeded() {
        var call = Async.run(() -> {});

        assertThrows(AssertionError.class, call::joinFailure);
    }

    @Test
    void awaitParkedOrDoneReturnsForAParkedThread() throws Exception {
        var gate = new CountDownLatch(1);
        var call = Async.run(gate::await);

        call.awaitParkedOrDone();
        assertFalse(call.isDone());

        gate.countDown();
        call.join();
    }

    @Test
    void awaitParkedOrDoneReturnsForAFinishedCall() {
        var call = Async.run(() -> {});

        call.awaitParkedOrDone();
        assertTrue(call.isDone());
    }
}
