package dev.pironi.tool;

import dev.pironi.agent.SubagentEvents;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Owns the lifecycle of cloud-only sub-agents. Each child runs on its own virtual thread against
 * the same stateless {@code ModelClient}, under a hard deadline so a stuck one cannot leak forever.
 *
 * <p>Cancellation goes only through {@link #cancelChild}, which sets the token before interrupting
 * and interrupts only a {@code RUNNING} handle. Interrupting a registered but still {@code NEW}
 * thread left a sticky flag that survived {@code start()} and killed the child's first blocking
 * call.
 */
public final class SubagentManager implements AutoCloseable, SubagentGateway {
    /** Records a child finished (or failed) and is ready to be drained. */
    public record Completion(String id, String name, SubagentResult result) {
    }

    private enum State { NEW, RUNNING, DONE, FAILED, CANCELLED }

    private static final class ChildHandle {
        final String id;
        final String name;
        final long startedNanos;
        volatile Thread thread;
        volatile State state = State.NEW;
        volatile SubagentResult.CancelReason cancelReason = SubagentResult.CancelReason.NONE;
        ChildHandle(String id, String name) {
            this.id = id;
            this.name = name;
            this.startedNanos = System.nanoTime();
        }
    }

    private final int maxConcurrent;
    private final java.util.function.BiFunction<String, String, SubagentResult> taskRunner;
    private final SubagentEvents events;
    private final AtomicInteger active = new AtomicInteger();
    private final LinkedBlockingQueue<Completion> completed = new LinkedBlockingQueue<>();
    private final ConcurrentHashMap<String, ChildHandle> children = new ConcurrentHashMap<>();
    private final Duration defaultTimeout;

    public SubagentManager(
            int maxConcurrent,
            java.util.function.BiFunction<String, String, SubagentResult> taskRunner
    ) {
        this(maxConcurrent, Duration.ofSeconds(120), SubagentEvents.NOOP, taskRunner);
    }

    public SubagentManager(
            int maxConcurrent,
            Duration defaultTimeout,
            java.util.function.BiFunction<String, String, SubagentResult> taskRunner
    ) {
        this(maxConcurrent, defaultTimeout, SubagentEvents.NOOP, taskRunner);
    }

    public SubagentManager(
            int maxConcurrent,
            Duration defaultTimeout,
            SubagentEvents events,
            java.util.function.BiFunction<String, String, SubagentResult> taskRunner
    ) {
        if (maxConcurrent < 1) throw new IllegalArgumentException("max-subagents must be >= 1");
        this.maxConcurrent = maxConcurrent;
        this.defaultTimeout = defaultTimeout;
        this.events = events == null ? SubagentEvents.NOOP : events;
        this.taskRunner = taskRunner;
    }

    /** Spawn a child for {@code subtask}. Returns a handle; the {@code substring} stays the real name. */
    public SubagentResult spawn(String name, String subtask) {
        if (active.get() >= maxConcurrent) {
            return SubagentResult.error(
                    "cap", name, "sub-agent limit reached (" + maxConcurrent + "); wait for a child to finish"
            );
        }
        String id = "sub_" + UUID.randomUUID().toString().substring(0, 8);
        ChildHandle handle = new ChildHandle(id, name);
        active.incrementAndGet();
        events.onSpawn(name, subtask);
        Thread child = Thread.ofVirtual()
                .name("subagent-" + id)
                .unstarted(() -> runChild(handle, subtask));
        synchronized (handle) {
            handle.thread = child;
            children.put(id, handle);
            handle.state = State.RUNNING;
            child.start(); // no external observer can see NEW; interrupt can't hit a null thread
        }
        return SubagentResult.completed(id, name, "started (handle " + id + ", concurrent "
                + active.get() + "/" + maxConcurrent + ")");
    }

    /**
     * The only cancellation path: token first, and only a {@code RUNNING} handle, so an unstarted
     * child exits through the prologue instead of inheriting a sticky interrupt flag.
     */
    private void cancelChild(ChildHandle h, SubagentResult.CancelReason reason) {
        synchronized (h) {
            if (h.state == State.DONE || h.state == State.FAILED
                    || h.cancelReason != SubagentResult.CancelReason.NONE) {
                return;
            }
            h.cancelReason = reason;
            State prev = h.state;
            h.state = State.CANCELLED;
            if (prev == State.RUNNING && h.thread != null) {
                h.thread.interrupt();
            }
            if (prev != State.CANCELLED) {
                log("cancel child id=" + h.id + " name=" + h.name + " prevState=" + prev + " reason=" + reason);
            }
        }
    }

    /** Cancels all children still alive (shutdown path). */
    public void cancelAll(String reasonTag) {
        cancelAll(reasonTag, children.keySet());
    }

    /** Cancels only the given child ids (used by the barrier snapshot). */
    public void cancelAll(String reasonTag, java.util.Set<String> ids) {
        SubagentResult.CancelReason reason = "timeout".equals(reasonTag)
                ? SubagentResult.CancelReason.TIMEOUT : SubagentResult.CancelReason.DISCARDED;
        ids.forEach(id -> {
            ChildHandle h = children.get(id);
            if (h != null) cancelChild(h, reason);
        });
    }

    private java.util.Set<String> activeIds() {
        java.util.Set<String> ids = new java.util.HashSet<>();
        children.values().forEach(h -> {
            if (h.state == State.NEW || h.state == State.RUNNING) ids.add(h.id);
        });
        return ids; // mutable (callers may remove() completed ids)
    }

    private void runChild(ChildHandle h, String subtask) {
        // Atomic prologue: clear any stale pre-start flag, then consult the token.
        // The flag is transport; the token is truth, so reading the token after clearing is safe
        // and cannot "eat" a legitimate cancel (no one interrupts without first setting the token).
        boolean hadFlag = Thread.interrupted();
        if (h.cancelReason != SubagentResult.CancelReason.NONE) {
            finish(h, SubagentResult.cancelled(h.id, h.name, h.cancelReason));
            return;
        }
        if (hadFlag) {
            log("stale pre-start interrupt cleared id=" + h.id + " name=" + h.name
                    + " (interrupt without a cancel token)");
        }
        SubagentResult result;
        try {
            result = taskRunner.apply(h.name, subtask);
            if (result == null) {
                result = SubagentResult.error(h.id, h.name, "sub-agent produced no result");
            }
            if (h.cancelReason != SubagentResult.CancelReason.NONE) {
                result = SubagentResult.cancelled(h.id, h.name, h.cancelReason);
            } else if (!h.id.equals(result.id()) || !h.name.equals(result.name())) {
                result = new SubagentResult(h.id, h.name, result.status(),
                        result.output(), result.activity(), result.elapsed());
            }
        } catch (Throwable t) {
            result = Thread.currentThread().isInterrupted()
                    && h.cancelReason != SubagentResult.CancelReason.NONE
                    ? SubagentResult.cancelled(h.id, h.name, h.cancelReason)
                    : SubagentResult.error(h.id, h.name, t.getMessage() == null
                            ? t.getClass().getSimpleName() : t.getMessage());
        }
        finish(h, result);
    }

    private void finish(ChildHandle h, SubagentResult result) {
        // Fill in the real elapsed from spawn (banner accuracy) before it leaves the manager.
        java.time.Duration elapsed = java.time.Duration.ofNanos(System.nanoTime() - h.startedNanos);
        SubagentResult measured = new SubagentResult(
                result.id(), result.name(), result.status(), result.output(), result.activity(), elapsed);
        // Publish the result BEFORE the child stops counting as active. awaitCompleted decides
        // whether to wait from activeIds() and the active count, so a child that is already
        // finished but whose result is not yet queued makes it return empty and lose the result
        // outright. Enqueueing first closes that window; a waiter simply drains it.
        completed.add(new Completion(h.id, h.name, measured));
        synchronized (h) {
            h.state = result.isCancelled() ? State.CANCELLED
                    : (result.status().equals("error") ? State.FAILED : State.DONE);
        }
        active.decrementAndGet();
        events.onDone(measured);
    }

    @Override
    public List<SubagentResult> awaitCompleted(Duration timeout) throws InterruptedException {
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException(
                    "awaitCompleted requires a positive timeout; for a non-blocking peek use drainCompleted()");
        }
        // Snapshot of children active at call time. A child spawned DURING this barrier wait must
        // NOT be cancelled by it (it gets its own execution window); only the original set is.
        java.util.Set<String> waitingOn = activeIds();
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        List<SubagentResult> out = new ArrayList<>();
        drainInto(out, waitingOn);
        while (!waitingOn.isEmpty() && active.get() > 0) {
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0) break;
            Completion next = completed.poll(remaining, TimeUnit.NANOSECONDS);
            if (next == null) break;
            out.add(next.result());
            waitingOn.remove(next.id());
            drainInto(out, waitingOn);
        }
        if (!waitingOn.isEmpty() && active.get() > 0) {
            // Bounded wait expired with original children still running: cancel the snapshot set.
            cancelAll("timeout", waitingOn);
        }
        // Always drain last. A child that finished between the first drain and the loop condition
        // leaves the queue non-empty while active has already dropped to zero, so the loop is
        // skipped and its result would otherwise be returned to nobody.
        drainInto(out, waitingOn);
        return out;
    }

    /**
     * Drains the queue and retires every child it carried. Draining without retiring made a
     * finished child cost a full timeout: finish() queues the result before it clears the active
     * count, so a caller in that window took the result and then waited on an empty queue.
     */
    private void drainInto(List<SubagentResult> out, java.util.Set<String> waitingOn) {
        Completion next;
        while ((next = completed.poll()) != null) {
            out.add(next.result());
            waitingOn.remove(next.id());
        }
    }

    /** Returns one ready child result, or null when nothing has finished. Kept for CLI convenience. */
    public Completion poll() {
        return completed.poll();
    }

    /** Drains all finished children into a list (FIFO). Empty when none finished. */
    public java.util.List<Completion> drain() {
        java.util.List<Completion> out = new java.util.ArrayList<>();
        Completion next;
        while ((next = completed.poll()) != null) {
            out.add(next);
        }
        return out;
    }

    /** Non-blocking drain of ready child results, oldest first. Empty when none finished. */
    public List<SubagentResult> drainCompleted() {
        List<SubagentResult> out = new ArrayList<>();
        Completion next;
        while ((next = completed.poll()) != null) {
            out.add(next.result());
        }
        return out;
    }

    /** Waits up to {@code grace} for active children, then cancels survivors and clears pending. */
    public void shutdownGracefully(Duration grace) {
        try {
            awaitCompleted(grace);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        cancelAll("shutdown");
        completed.clear();
    }

    @Override
    public List<String> runningHandles() {
        List<String> handles = new ArrayList<>();
        children.values().forEach(h -> {
            if (h.state == State.NEW || h.state == State.RUNNING) {
                handles.add(h.id + " (" + h.name + ")");
            }
        });
        return handles;
    }

    @Override
    public void discardPending() {
        // Only drop undelivered results. Does NOT interrupt children — cancelling kids is a
        // separate, explicit operation (cancelAll / shutdownGracefully). This is the fix for the
        // 0ms InterruptedException: a fresh spawn must not be killed by a routine pending-drain.
        completed.clear();
    }

    /**
     * Drops pending results and cancels any children still NEW/RUNNING (prefixed discard).
     * Used only where the caller really wants pending children gone, e.g. shutdown.
     */
    public void discardPendingResults() {
        completed.clear();
        cancelAll("discarded");
    }

    @Override
    public int activeCount() {
        return active.get();
    }

    public int maxConcurrent() {
        return maxConcurrent;
    }

    public Duration defaultTimeout() {
        return defaultTimeout;
    }

    @Override
    public void close() {
        cancelAll("shutdown");
    }

    private static void log(String message) { }  // hooks in here for diagnostics if needed
}
