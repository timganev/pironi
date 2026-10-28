package dev.pironi.swarm;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * Decides when a model request may go out, so that many agents on one card do not choke it.
 *
 * <p>The card chokes on prefill, not on the number of requests: a dozen agents that share a cached
 * prefix run fine, four that each bring 15k new tokens queue behind one another. So a request is
 * admitted only when both hold:
 * <ul>
 *   <li>fewer than {@code window} requests are in flight;</li>
 *   <li>the uncached tokens still being prefilled, plus this request's, fit in {@code budget}.</li>
 * </ul>
 * A request's uncached tokens stop counting once the measured prefill rate says they are done, so
 * the budget limits prefill without serialising the decode that follows it. A request alone in
 * flight is always admitted, however large: otherwise one long prompt would wait forever.
 *
 * <p>Window and budget follow AIMD, as TCP does with congestion: each green reading adds one to
 * the window while there is demand for it, the first red one halves it and cuts the budget by a
 * quarter. After a cut the next readings are ignored for a while, because the KV cache and the
 * queue take seconds to drain and would otherwise halve the window again for the same overload.
 *
 * <p>Requests are admitted in arrival order. A pause only ever happens before a request is sent,
 * that is between an agent's turns; nothing is interrupted once it is on the wire.
 */
public final class DispatchController {
    public enum Light { GREEN, YELLOW, RED, UNKNOWN }

    static final double KV_YELLOW = 0.60;
    static final double KV_RED = 0.85;
    static final double QUEUE_YELLOW_SECONDS = 0.5;
    static final double QUEUE_RED_SECONDS = 2.0;
    static final double HIT_RED = 0.5;
    /** Readings ignored after a cut, so one overload is not punished twice. */
    static final int COOLDOWN_TICKS = 3;
    static final double DEFAULT_PREFILL_RATE = 4_000;

    private final int maxWindow;
    private final long maxBudget;
    private final long minBudget;
    private final LongSupplier clock;

    private int window;
    private long budget;
    private double prefillRate = DEFAULT_PREFILL_RATE;
    private int cooldown;
    private Light light = Light.UNKNOWN;
    private final ArrayDeque<Object> queue = new ArrayDeque<>();
    private final List<Permit> inFlight = new ArrayList<>();

    /** An admitted request; hand it back to {@link #release}. */
    public static final class Permit {
        final long tokens;
        final long admittedNanos;

        Permit(long tokens, long admittedNanos) {
            this.tokens = tokens;
            this.admittedNanos = admittedNanos;
        }
    }

    public DispatchController(int initialWindow, int maxWindow, long budgetTokens) {
        this(initialWindow, maxWindow, budgetTokens, System::nanoTime);
    }

    DispatchController(int initialWindow, int maxWindow, long budgetTokens, LongSupplier clock) {
        if (maxWindow < 1 || initialWindow < 1 || budgetTokens < 1) {
            throw new IllegalArgumentException("window and budget must be positive");
        }
        this.maxWindow = maxWindow;
        this.window = Math.min(initialWindow, maxWindow);
        this.maxBudget = budgetTokens;
        this.budget = budgetTokens;
        this.minBudget = Math.max(1, budgetTokens / 4);
        this.clock = clock;
    }

    /** Blocks until the request may go out. */
    public Permit acquire(long uncachedTokens) throws InterruptedException {
        Object ticket = new Object();
        synchronized (this) {
            queue.addLast(ticket);
            try {
                while (queue.peekFirst() != ticket || !admissible(uncachedTokens)) {
                    // Timed: budget frees up as prefill progresses, with no event to wake on.
                    wait(100);
                }
            } finally {
                queue.remove(ticket);
                notifyAll();
            }
            Permit permit = new Permit(Math.max(0, uncachedTokens), clock.getAsLong());
            inFlight.add(permit);
            return permit;
        }
    }

    /** Non-blocking variant for tests and callers that would rather do something else. */
    public synchronized Permit tryAcquire(long uncachedTokens) {
        if (!queue.isEmpty() || !admissible(uncachedTokens)) return null;
        Permit permit = new Permit(Math.max(0, uncachedTokens), clock.getAsLong());
        inFlight.add(permit);
        return permit;
    }

    public synchronized void release(Permit permit) {
        if (inFlight.remove(permit)) notifyAll();
    }

    private boolean admissible(long tokens) {
        if (inFlight.isEmpty()) return true;
        return inFlight.size() < window && prefillPending() + tokens <= budget;
    }

    /** Uncached tokens of the requests in flight that the measured rate says are not yet prefilled. */
    synchronized long prefillPending() {
        long now = clock.getAsLong();
        long pending = 0;
        for (Permit p : inFlight) {
            double done = (now - p.admittedNanos) / 1e9 * prefillRate;
            pending += Math.max(0, p.tokens - (long) done);
        }
        return pending;
    }

    /** Feeds one sensor reading; returns the light it was read as. */
    public synchronized Light onTick(Sensors s) {
        if (s.available() && s.prefillRate() > 0 && Double.isFinite(s.prefillRate())) {
            prefillRate = 0.7 * prefillRate + 0.3 * s.prefillRate();
        }
        light = classify(s);
        if (cooldown > 0) {
            cooldown--;
        } else if (light == Light.RED) {
            window = Math.max(1, window / 2);
            budget = Math.max(minBudget, (long) (budget * 0.75));
            cooldown = COOLDOWN_TICKS;
        } else if (light == Light.GREEN) {
            // Grow only while the window is what holds requests back; an idle swarm says nothing
            // about whether a wider one would fit.
            if (inFlight.size() >= window || !queue.isEmpty()) {
                window = Math.min(maxWindow, window + 1);
            }
            budget = Math.min(maxBudget, (long) (budget * 1.25));
        }
        notifyAll();
        return light;
    }

    static Light classify(Sensors s) {
        if (s.rateLimited()) return Light.RED;
        if (!s.available()) return Light.UNKNOWN;
        boolean highKv = s.kvUsage() > KV_RED;
        boolean thrashing = s.prefixHitRate() < HIT_RED && s.kvUsage() >= KV_YELLOW;
        if (s.preemptions() > 0 || highKv || s.queueSeconds() > QUEUE_RED_SECONDS || thrashing) {
            return Light.RED;
        }
        if (s.kvUsage() >= KV_YELLOW || s.queueSeconds() >= QUEUE_YELLOW_SECONDS || s.waiting() > 0) {
            return Light.YELLOW;
        }
        return Light.GREEN;
    }

    public synchronized int window() { return window; }
    public synchronized long budget() { return budget; }
    public synchronized int inFlight() { return inFlight.size(); }
    public synchronized int queued() { return queue.size(); }
    public synchronized double prefillRate() { return prefillRate; }
    public synchronized Light light() { return light; }
}
