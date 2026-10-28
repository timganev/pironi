package dev.pironi.swarm;

import java.util.HashMap;
import java.util.Map;

/**
 * How many model requests to keep in flight, learned from the answers alone, so it works on any
 * OpenAI-compatible server - vLLM, llama.cpp, Ollama, LM Studio, MLX or a cloud API - with no
 * metrics endpoint at all.
 *
 * <p>The idea is Netflix's concurrency-limits (Vegas, Gradient2): latency says when a server is
 * full. A model server needs one twist. Batching makes every request slower as more run at once
 * even while the total work done grows, so a rise in latency alone is no sign of overload. What
 * is: latency rising as fast as concurrency, with no gain in throughput, because the extra
 * requests only wait in the server's queue. So each limit is tried while there is demand for it,
 * and the throughput it gave is compared with the best limit known so far:
 * <pre>
 *   throughput  T = mean(concurrency x tokens / duration)            (Little's law, per answer)
 *   payoff      φ = log(T_new / T_good) / log(C_new / C_good)        (1 = linear, 0 = none)
 * </pre>
 * A limit pays off when φ is at least {@link #GAIN_FLOOR}. Answers differ in size, so T is noisy:
 * a limit is judged early only when φ is clear of the floor by a standard error; otherwise the
 * trial is extended, up to {@link #MAX_TRIAL_FACTOR} times, and then φ itself decides. Limits are searched like TCP slow start
 * and then bisected: 1, 2, 4, 8 ... until one does not pay off, then halfway back until the two
 * meet. A settled limit is re-probed half again higher from time to time, because the server or its
 * load may have changed.
 *
 * <p>Tokens are normalised so a long prompt does not look like a slow server: a generated token
 * counts 1, an uncached prompt token {@link #PREFILL_WEIGHT}.
 *
 * <p>Overload (429, 503, a timeout, Retry-After, or a red reading from /metrics) halves the limit
 * at once, as Gradient2 does, and so does throughput at a settled limit falling below
 * 1/{@link #CONTENTION_TOLERANCE} of its long-run level: someone else is using the server.
 *
 * <p>Not thread safe; {@link DispatchController} calls it under its own lock.
 */
public final class ConcurrencyLimiter {
    /** A prefilled prompt token costs about this share of a generated one on common servers. */
    static final double PREFILL_WEIGHT = 0.05;
    /** The share of a linear speed-up a wider limit must give to be kept. */
    static final double GAIN_FLOOR = 0.3;
    /** Without a ceiling from the user or the server, this many is the most ever tried. */
    public static final int SAFETY_CEILING = 64;
    /**
     * Answers a limit is first judged on: this many, or one per slot. Few, because on a real model
     * one answer can take ten seconds; when they are not enough the trial is extended (see above).
     */
    static final int MIN_TRIAL_SAMPLES = 4;
    /** Samples at a settled limit before the next step up is tried. */
    static final int PROBE_AFTER_SAMPLES = 60;
    static final double CONTENTION_TOLERANCE = 2.0;
    static final int MAX_TRIAL_FACTOR = 4;
    /**
     * Answers of different sizes differ in throughput by at least this much (coefficient of
     * variation); a spread measured on four answers can be far too small, and would let noise pass.
     */
    static final double MIN_SPREAD = 0.3;
    private static final double LONG_ALPHA = 0.2;

    private final int ceiling;
    private int limit = 1;
    private int good = 1;
    private int bad = Integer.MAX_VALUE;
    private boolean settled;
    private boolean growthAllowed = true;
    /** A limit left unclear, while the good one is measured longer; 0 when none. */
    private int pending;
    private Trial pendingTrial;
    /** The last limit whose comparison was re-run, so each is re-run once. */
    private int rerun;
    /** Answers still to skip at a new limit; see {@link #moveTo}. */
    private int warmup;
    private int epoch;
    private int settledSamples;
    private Trial trial = new Trial();
    private final Map<Integer, Trial> measured = new HashMap<>();
    private double longThroughput = Double.NaN;

    /** What one limit gave while it was full. */
    private static final class Trial {
        double sum;  // of each answer's concurrency x tokens / seconds
        double sumSquares;
        double seconds;
        double concurrencySeconds;
        int samples;

        void add(double throughput, double concurrency, double s) {
            sum += throughput;
            sumSquares += throughput * throughput;
            seconds += s;
            concurrencySeconds += concurrency * s;
            samples++;
        }

        double throughput() { return sum / samples; }
        double concurrency() { return concurrencySeconds / seconds; }

        /** Standard error of log(throughput). */
        double logError() {
            double mean = throughput();
            double variance = Math.max(0, sumSquares / samples - mean * mean) * samples / Math.max(1, samples - 1);
            return Math.max(Math.sqrt(variance) / mean, MIN_SPREAD) / Math.sqrt(samples);
        }
    }

    public ConcurrencyLimiter(int ceiling) {
        this(ceiling, 1);
    }

    /** Starts the search at {@code start} rather than 1; for tests. */
    ConcurrencyLimiter(int ceiling, int start) {
        this.ceiling = Math.max(1, ceiling);
        this.limit = this.good = Math.max(1, Math.min(start, this.ceiling));
    }

    public int limit() { return limit; }
    public int ceiling() { return ceiling; }
    /** Whether the search has found its limit, rather than still trying wider ones. */
    public boolean settled() { return settled; }
    /** Changes with every new limit; a request admitted under an older one says nothing about it. */
    public int epoch() { return epoch; }

    /** False while /metrics reads yellow: the server is busy, so a wider limit is not tried. */
    public void allowGrowth(boolean allowed) { growthAllowed = allowed; }

    /**
     * One finished request.
     *
     * @param seconds     from sending it to the last token
     * @param tokens      generated tokens plus {@link #PREFILL_WEIGHT} x uncached prompt tokens
     * @param concurrency requests in flight on average while it ran, itself included
     * @param saturated   whether others were held back while it ran: only then was the limit in use
     * @param admittedIn  the {@link #epoch()} it was admitted in
     */
    public void onSample(double seconds, double tokens, double concurrency, boolean saturated, int admittedIn) {
        if (seconds <= 0 || tokens <= 0 || concurrency <= 0) return;
        if (admittedIn != epoch || !saturated) return;
        if (warmup > 0) {
            warmup--;
            return;
        }
        trial.add(concurrency * tokens / seconds, concurrency, seconds);
        if (trial.samples < trialSize() || trial.samples % trialSize() != 0) return;
        if (pending > 0) {
            // The good limit, measured longer; then back to the unclear one, with its answers kept.
            if (trial.samples < MAX_TRIAL_FACTOR * trialSize()) return;
            measured.put(good, trial);
            int back = pending;
            Trial kept = pendingTrial;
            pending = 0;
            pendingTrial = null;
            moveTo(back);
            trial = kept;
            return;
        }
        if (settled) {
            if (contended(trial.throughput())) return;
            measured.put(limit, trial);
            settledSamples += trial.samples;
            trial = new Trial();
            if (settledSamples >= PROBE_AFTER_SAMPLES && limit < ceiling && growthAllowed) {
                // The server may have grown. A step of half again, not one: one request more changes
                // throughput by less than the noise between answers of different sizes.
                bad = Integer.MAX_VALUE;
                settled = false;
                moveTo(Math.max(limit + 1, (int) Math.ceil(limit * 1.5)));
            }
            return;
        }
        decide();
    }

    private int trialSize() {
        return Math.max(MIN_TRIAL_SAMPLES, limit);
    }

    private void decide() {
        Trial now = trial;
        Trial base = measured.get(good);
        if (base == null || limit == good) {
            measured.put(limit, now);
            next();
            return;
        }
        double concurrencyGain = now.concurrency() / base.concurrency();
        if (concurrencyGain < 1.15) {
            // The swarm never filled the wider limit; nothing was learned about it.
            trial = new Trial();
            return;
        }
        double logGain = Math.log(now.throughput() / base.throughput());
        double error = Math.hypot(now.logError(), base.logError());
        double scale = Math.log(concurrencyGain);
        boolean pays = (logGain - error) / scale >= GAIN_FLOOR;
        boolean doesNot = (logGain + error) / scale < GAIN_FLOOR;
        if (!pays && !doesNot) {
            if (now.samples < MAX_TRIAL_FACTOR * trialSize()) return;  // not clear yet
            if (rerun != limit && base.samples < MAX_TRIAL_FACTOR * trialSize()) {
                // Most of the doubt is the good limit's short first measurement: measure it longer.
                rerun = limit;
                pending = limit;
                pendingTrial = now;
                Trial longer = base;
                moveTo(good);
                trial = longer;
                return;
            }
            // Still unclear with both limits measured at length: a gain too small to see is not
            // worth a fuller server, and counting it would let noise walk the limit upward.
            pays = false;
        }
        measured.put(limit, now);
        if (pays) {
            good = limit;
        } else {
            bad = limit;
        }
        next();
    }

    private void next() {
        int target = bad == Integer.MAX_VALUE ? good * 2 : (good + bad) / 2;
        target = Math.min(target, ceiling);
        if (target <= good || !growthAllowed) {
            settled = true;
            settledSamples = 0;
            moveTo(good);
        } else {
            moveTo(target);
        }
    }

    /**
     * At a settled limit, a trial whose throughput fell well below the long-run level: per-token
     * latency at the same concurrency is up, so someone else is using the server. Judged per trial,
     * not per answer, because one answer's latency depends mostly on its size.
     */
    private boolean contended(double throughput) {
        if (Double.isNaN(longThroughput)) {
            longThroughput = throughput;
            return false;
        }
        if (throughput * CONTENTION_TOLERANCE < longThroughput && limit > 1) {
            overload();
            return true;
        }
        longThroughput += LONG_ALPHA * (throughput - longThroughput);
        return false;
    }

    /** 429, 503, a timeout or a red reading: halve now and settle below where it happened. */
    public void overload() {
        int before = limit;
        good = Math.max(1, limit / 2);
        bad = before > good ? before : Integer.MAX_VALUE;
        settled = true;
        settledSamples = 0;
        pending = rerun = 0;
        pendingTrial = null;
        measured.clear();
        moveTo(good);
    }

    /**
     * Right after a change the first answers back are the quick ones - the slow ones are still
     * running - so they overstate throughput at the new limit. Half a limit's worth is skipped.
     */
    private void moveTo(int newLimit) {
        limit = Math.max(1, Math.min(newLimit, ceiling));
        warmup = limit / 2;
        epoch++;
        trial = new Trial();
        longThroughput = Double.NaN;
    }
}
