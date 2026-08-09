package dev.pironi.tool;

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
 * Owns the lifecycle of cloud-only sub-agents.
 *
 * <p>Design (from the Pironi Triad plan): the main agent loop blocks on a barrier right
 * after executing tools, so the model has no turn between spawning a child and receiving
 * its result — making duplicated work structurally impossible. Each child runs in its own
 * virtual thread against the same (stateless HTTP) {@code ModelClient}. A hard deadline
 * interrupts a stuck child so it cannot leak tokens/RAM forever.
 */
public final class SubagentManager implements AutoCloseable, SubagentGateway {
    /** Records a child finished (or failed) and is ready to be drained. */
    public record Completion(String id, String name, SubagentResult result) {
    }

    private final int maxConcurrent;
    private final Function<String, SubagentResult> taskRunner;
    private final AtomicInteger active = new AtomicInteger();
    private final LinkedBlockingQueue<Completion> completed = new LinkedBlockingQueue<>();
    private final ConcurrentHashMap<String, String> running = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Thread> childThreads = new ConcurrentHashMap<>();
    private final Duration defaultTimeout;

    public SubagentManager(
            int maxConcurrent,
            Function<String, SubagentResult> taskRunner
    ) {
        this(maxConcurrent, Duration.ofSeconds(120), taskRunner);
    }

    public SubagentManager(
            int maxConcurrent,
            Duration defaultTimeout,
            Function<String, SubagentResult> taskRunner
    ) {
        if (maxConcurrent < 1) throw new IllegalArgumentException("max-subagents must be >= 1");
        this.maxConcurrent = maxConcurrent;
        this.defaultTimeout = defaultTimeout;
        this.taskRunner = taskRunner;
    }

    /**
     * Spawn a child for {@code subtask}. Returns a handle immediately. If the concurrency
     * cap is reached, the result is a failure and nothing is queued.
     */
    public SubagentResult spawn(String name, String subtask) {
        if (active.get() >= maxConcurrent) {
            return SubagentResult.error(
                    "cap", name, "sub-agent limit reached (" + maxConcurrent + "); wait for a child to finish"
            );
        }
        String id = "sub_" + UUID.randomUUID().toString().substring(0, 8);
        active.incrementAndGet();
        running.put(id, name);
        Thread child = Thread.ofVirtual()
                .name("subagent-" + id)
                .unstarted(() -> runChild(id, name, subtask));
        childThreads.put(id, child);
        child.start();
        return SubagentResult.completed(id, name, "started (handle " + id + ", concurrent "
                + active.get() + "/" + maxConcurrent + ")");
    }

    private void runChild(String id, String name, String subtask) {
        SubagentResult result;
        if (Thread.currentThread().isInterrupted()) {
            result = SubagentResult.error(id, name, "did not finish within timeout");
        } else {
            try {
                result = taskRunner.apply(subtask);
                if (result == null) {
                    result = SubagentResult.error(id, name, "sub-agent produced no result");
                }
                // Normalize: the child's taskRunner may return a hardcoded id ("child"); rewrite
                // it to the real handle so the model matches the spawn response to the result.
                if (!id.equals(result.id())) {
                    result = new SubagentResult(id, result.name(), result.status(),
                            result.output(), result.activity());
                }
            } catch (Throwable t) {
                result = Thread.currentThread().isInterrupted()
                        ? SubagentResult.error(id, name, "did not finish within timeout")
                        : SubagentResult.error(id, name, t.getMessage() == null
                                ? t.getClass().getSimpleName() : t.getMessage());
            }
        }
        childThreads.remove(id);
        running.remove(id);
        active.decrementAndGet();
        completed.add(new Completion(id, name, result));
    }

    @Override
    public List<SubagentResult> awaitCompleted(Duration timeout) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        List<SubagentResult> out = new ArrayList<>();
        drainInto(out);
        while (active.get() > 0) {
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0) break;
            Completion next = completed.poll(remaining, TimeUnit.NANOSECONDS);
            if (next == null) break;
            out.add(next.result());
            drainInto(out);
        }
        if (active.get() > 0) {
            // Deadline reached with children still running: interrupt them so a stuck child
            // cannot keep consuming tokens/RAM forever. runChild resolves it as a timeout result.
            childThreads.values().forEach(Thread::interrupt);
        }
        return out;
    }

    private void drainInto(List<SubagentResult> out) {
        Completion next;
        while ((next = completed.poll()) != null) {
            out.add(next.result());
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

    @Override
    public List<String> runningHandles() {
        List<String> handles = new ArrayList<>();
        running.forEach((id, name) -> handles.add(id + " (" + name + ")"));
        return handles;
    }

    @Override
    public void discardPending() {
        completed.clear();
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
        childThreads.values().forEach(Thread::interrupt);
    }
}
