package dev.pironi.tool;

import java.time.Duration;
import java.util.List;

/**
 * Abstraction over {@link SubagentManager} that keeps {@code AgentLoop} decoupled from the
 * executor details. Exists so a fake (or in-memory) gateway can drive {@code AgentLoop} tests
 * deterministically without spawning real virtual threads.
 */
public interface SubagentGateway {
    /**
     * Blocks until all currently active children finish or {@code timeout} elapses, returning
     * whichever results are ready. {@code Duration.ZERO} behaves like a non-blocking drain of
     * already-completed children.
     *
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    List<SubagentResult> awaitCompleted(Duration timeout) throws InterruptedException;

    /** Human-readable handle(s) of children still running, e.g. {@code [sub_ab12 (fetch-prices)]}. */
    List<String> runningHandles();

    /** Number of children currently active (spawned but not finished). */
    int activeCount();

    /** Drop any stale results from a previous run so they don't leak into a new task. */
    void discardPending();
}
