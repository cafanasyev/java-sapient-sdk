package io.sapient.testing;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

/** A call running on a virtual thread of its own, so the test can do something while it runs. */
public final class Async {

    private final FutureTask<Void> task;
    private final Thread thread;

    private Async(ThrowingRunnable call) {
        task =
                new FutureTask<>(
                        () -> {
                            call.run();
                            return null;
                        });
        thread = Thread.ofVirtual().start(task);
    }

    /** Starts {@code call} on a new virtual thread. */
    public static Async run(ThrowingRunnable call) {
        return new Async(call);
    }

    /**
     * Blocks until the call has finished or its thread is parked or blocked. It replaces "give the
     * other thread some time". A thread can park for an unrelated reason, logging for example; then
     * a test may miss a regression, but it does not fail falsely.
     */
    public void awaitParkedOrDone() {
        while (!task.isDone() && !isParked()) {
            if (Thread.currentThread().isInterrupted()) {
                throw new AssertionError("still waiting for the call to park or finish");
            }
            Thread.onSpinWait();
        }
    }

    public boolean isDone() {
        return task.isDone();
    }

    public void interrupt() {
        thread.interrupt();
    }

    /** Waits for the call and rethrows whatever it threw. */
    public void join() throws Exception {
        Throwable failure = failure();
        if (failure instanceof Exception exception) {
            throw exception;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    /** Waits for the call and returns what it threw; fails the test if it threw nothing. */
    public Throwable joinFailure() {
        Throwable failure = failure();
        if (failure == null) {
            throw new AssertionError("the call finished without an exception");
        }
        return failure;
    }

    public static void joinAll(Async... calls) throws Exception {
        for (Async call : calls) {
            call.join();
        }
    }

    private boolean isParked() {
        return switch (thread.getState()) {
            case WAITING, TIMED_WAITING, BLOCKED -> true;
            case NEW, RUNNABLE, TERMINATED -> false;
        };
    }

    private Throwable failure() {
        try {
            task.get();
            return null;
        } catch (ExecutionException e) {
            return e.getCause();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("still waiting for the call to finish", e);
        }
    }
}
