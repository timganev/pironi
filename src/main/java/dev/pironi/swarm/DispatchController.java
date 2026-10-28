package dev.pironi.swarm;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * Decides when a model request may go out, so that many agents on one server do not choke it.
 *
 * <p>Two limits, both of which must hold:
 * <ul>
 *   <li>fewer requests in flight than the {@link ConcurrencyLimiter} has learned the server
 *       takes - from latency alone, so it works on any OpenAI-compatible server;</li>
 *   <li>when vLLM's /metrics is readable: the uncached tokens still being prefilled, plus this
 *       request's, fit in {@code budget}. A request's tokens stop counting once the measured
 *       prefill rate says they are done, so the budget limits prefill without serialising decode.</li>
 * </ul>
 * A request alone in flight is always admitted, however large: otherwise one long prompt would
 * wait forever.
 *
 * <p>/metrics, when there is one, only adds to what latency says. Red (preemptions, a full KV
 * cache, a long queue) halves the limit and cuts the budget by a quarter; yellow stops the limiter
 * from trying wider limits. After a cut the next readings are ignored for a while, because the KV
 * cache and the queue take seconds to drain and would otherwise halve the limit again for the same
 * overload. Without /metrics nothing here waits for it.
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

    private final ConcurrencyLimiter limiter;
    private final long maxBudget;
    private final long minBudget;
    private final LongSupplier clock;

    private long budget;
    /** The budget applies only once /metrics has answered: without it the prefill rate is a guess. */
    private boolean budgetActive;
    private double prefillRate = DEFAULT_PREFILL_RATE;
    private int cooldown;
    private Light light = Light.UNKNOWN;
    private final ArrayDeque<Object> queue = new ArrayDeque<>();
    private final List<Permit> inFlight = new ArrayList<>();
    /** Requests in flight integrated over time, in request-nanoseconds: gives a request's average concurrency. */
    private double concurrencyArea;
    private long areaUpdatedNanos;
    /** Times a request found the limit full; a request that saw it change ran while the limit was in use. */
    private long heldBack;

    /** An admitted request; hand it back to {@link #release}. */
    public static final class Permit {
        final long tokens;
        final long admittedNanos;
        final double areaAtAdmit;
        final long heldBackAtAdmit;
        final int epoch;

        Permit(long tokens, long admittedNanos, double areaAtAdmit, long heldBackAtAdmit, int epoch) {
            this.tokens = tokens;
            this.admittedNanos = admittedNanos;
            this.areaAtAdmit = areaAtAdmit;
            this.heldBackAtAdmit = heldBackAtAdmit;
            this.epoch = epoch;
        }
    }

    public DispatchController(int ceiling, long budgetTokens) {
        this(ceiling, budgetTokens, System::nanoTime);
    }

    DispatchController(int ceiling, long budgetTokens, LongSupplier clock) {
        this(ceiling, Math.min(ceiling, ConcurrencyLimiter.BLIND_START), budgetTokens, clock);
    }

    /** @param startLimit where the limit starts: the ceiling, or what was learned last time */
    public DispatchController(int ceiling, int startLimit, long budgetTokens) {
        this(ceiling, startLimit, budgetTokens, System::nanoTime);
    }

    DispatchController(int ceiling, int startLimit, long budgetTokens, LongSupplier clock) {
        if (ceiling < 1 || budgetTokens < 1) {
            throw new IllegalArgumentException("ceiling and budget must be positive");
        }
        this.limiter = new ConcurrencyLimiter(ceiling, startLimit);
        this.maxBudget = budgetTokens;
        this.budget = budgetTokens;
        this.minBudget = Math.max(1, budgetTokens / 4);
        this.clock = clock;
        this.areaUpdatedNanos = clock.getAsLong();
    }

    /** Blocks until the request may go out. */
    public Permit acquire(long uncachedTokens) throws InterruptedException {
        Object ticket = new Object();
        synchronized (this) {
            queue.addLast(ticket);
            boolean counted = false;
            try {
                while (queue.peekFirst() != ticket || !admissible(uncachedTokens)) {
                    if (!counted) {
                        heldBack++;
                        counted = true;
                    }
                    // Timed: budget frees up as prefill progresses, with no event to wake on.
                    wait(100);
                }
            } finally {
                queue.remove(ticket);
                notifyAll();
            }
            return admit(uncachedTokens);
        }
    }

    /** Non-blocking variant for tests and callers that would rather do something else. */
    public synchronized Permit tryAcquire(long uncachedTokens) {
        if (!queue.isEmpty() || !admissible(uncachedTokens)) {
            heldBack++;
            return null;
        }
        return admit(uncachedTokens);
    }

    private Permit admit(long uncachedTokens) {
        advanceArea();
        Permit permit = new Permit(Math.max(0, uncachedTokens), clock.getAsLong(), concurrencyArea,
                heldBack, limiter.epoch());
        inFlight.add(permit);
        return permit;
    }

    /** The request answered; its time and size teach the limiter. */
    public synchronized void release(Permit permit, long generatedTokens) {
        if (!inFlight.contains(permit)) return;
        advanceArea();
        long elapsed = clock.getAsLong() - permit.admittedNanos;
        double concurrency = elapsed > 0 ? (concurrencyArea - permit.areaAtAdmit) / elapsed : inFlight.size();
        // In use: others were held back, or it was full the whole time even with no one waiting.
        boolean wanted = heldBack != permit.heldBackAtAdmit || !queue.isEmpty();
        boolean saturated = wanted || concurrency >= limiter.limit() - 0.5;
        double tokens = generatedTokens + ConcurrencyLimiter.PREFILL_WEIGHT * permit.tokens;
        limiter.onSample(elapsed / 1e9, tokens, concurrency, saturated, wanted);
        remove(permit);
    }

    /** The request failed; an overload (429, 503, timeout) halves the limit, once per limit. */
    public synchronized void releaseFailed(Permit permit, boolean overloaded) {
        if (!inFlight.contains(permit)) return;
        if (overloaded && permit.epoch == limiter.epoch()) limiter.overload();
        advanceArea();
        remove(permit);
    }

    /** Returns the permit without teaching the limiter anything. */
    public synchronized void release(Permit permit) {
        if (!inFlight.contains(permit)) return;
        advanceArea();
        remove(permit);
    }

    private void advanceArea() {
        long now = clock.getAsLong();
        concurrencyArea += (double) inFlight.size() * (now - areaUpdatedNanos);
        areaUpdatedNanos = now;
    }

    private void remove(Permit permit) {
        inFlight.remove(permit);
        notifyAll();
    }

    private boolean admissible(long tokens) {
        if (inFlight.isEmpty()) return true;
        if (inFlight.size() >= limiter.limit()) return false;
        return !budgetActive || prefillPending() + tokens <= budget;
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

    /** Feeds one reading of /metrics (or of 429/503 alone); returns the light it was read as. */
    public synchronized Light onTick(Sensors s) {
        if (s.available()) budgetActive = true;
        if (s.available() && s.prefillRate() > 0 && Double.isFinite(s.prefillRate())) {
            // Already an average since the first scrape, so it is taken as it is.
            prefillRate = s.prefillRate();
        }
        light = classify(s);
        limiter.signal(light == Light.GREEN ? ConcurrencyLimiter.Signal.GREEN
                : light == Light.UNKNOWN ? ConcurrencyLimiter.Signal.NONE : ConcurrencyLimiter.Signal.HOLD);
        if (cooldown > 0) {
            cooldown--;
        } else if (light == Light.RED) {
            limiter.overload();
            budget = Math.max(minBudget, (long) (budget * 0.75));
            cooldown = COOLDOWN_TICKS;
        } else if (light == Light.GREEN) {
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

    /** A limit worth keeping for the next run: settled, and learned from the server rather than the demand. */
    public synchronized int learnedLimit() {
        return limiter.settled() && !limiter.demandBound() ? limiter.limit() : 0;
    }

    /** Holds the limit where it is, whatever happens; for comparisons in tests. */
    synchronized void fixLimit() {
        limiter.freeze();
    }

    /** What the swarm has learned the server takes; see {@link Parallelism}. */
    public synchronized Parallelism parallelism() {
        return new Parallelism(limiter.limit(), limiter.settled(), limiter.ceiling());
    }

    public synchronized int window() { return limiter.limit(); }
    public synchronized long budget() { return budget; }
    public synchronized boolean budgetActive() { return budgetActive; }
    public synchronized int inFlight() { return inFlight.size(); }
    public synchronized int queued() { return queue.size(); }
    public synchronized double prefillRate() { return prefillRate; }
    public synchronized Light light() { return light; }
}
