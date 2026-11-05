package dev.pironi.swarm;

import java.util.HashMap;
import java.util.Map;

/**
 * How many model requests to keep in flight, so it works on any OpenAI-compatible server - vLLM,
 * llama.cpp, Ollama, LM Studio, MLX or a cloud API - with or without a metrics endpoint.
 *
 * <p>The search runs from the top down, because on a model server one answer can take a minute and
 * the two mistakes do not cost the same. Too few requests in flight wastes the server for as long
 * as the search takes. Too many costs little: a server with fixed slots (llama.cpp, Ollama, LM
 * Studio) queues the extra ones itself, and vLLM only suffers once its KV cache runs out, which
 * /metrics and a fall in throughput both show. So the limit starts at what is known - the user's
 * ceiling, the server's slots, the limit learned last time, or else {@link #BLIND_START} - and is
 * lowered on a signal (AIMD):
 * <ul>
 *   <li>429, 503, a timeout, Retry-After, preemptions or a red reading: half, at once;</li>
 *   <li>a queue in the server that the limit builds: one less (right after a doubling, back to
 *       the limit before it);</li>
 *   <li>throughput well below the best seen (thrashing, or someone else on the server): one less.</li>
 * </ul>
 * It grows only while requests are held back and nothing says the server is busy: with /metrics,
 * on green; without it, on trial, kept only if it paid. From a cold start it doubles (slow start)
 * until the first sign of congestion - a cut, a step that did not pay, a fall in throughput - and
 * from then on grows by one; a start from a learned limit grows by one from the outset and tries
 * fewer only once one more did not pay, since that limit was measured under load. Without /metrics
 * it also tries a step down from time to time and stays there if that cost nothing, because only
 * that shows a server past its best: one with fixed slots gives the same with fewer, a thrashing
 * cache more. A limit the swarm does not fill for a whole wave comes down to what it asks for.
 *
 * <p>Throughput is measured in waves of finished requests, not per answer. By Little's law a request
 * that ran {@code s} seconds alongside {@code c} others on average stands for {@code c} requests'
 * worth of the server for that time, so a wave's throughput is {@code sum(tokens) / sum(s / c)}.
 * Unlike tokens over wall-clock time, this does not depend on where the wave's edges fall when
 * answers finish in bursts. A token counts 1 if
 * generated, {@link #PREFILL_WEIGHT} if it was an uncached prompt token, so a long prompt does not
 * look like a slow server. Only requests that ran while others were held back count: otherwise the
 * limit was not what set the pace.
 *
 * <p>Not thread safe; {@link DispatchController} calls it under its own lock.
 */
public final class ConcurrencyLimiter {
    /** A prefilled prompt token costs about this share of a generated one on common servers. */
    static final double PREFILL_WEIGHT = 0.05;
    /** Without a ceiling from the user or the server, this many is the most ever tried. */
    public static final int SAFETY_CEILING = 64;
    /** Where a server that tells nothing about itself starts. */
    public static final int BLIND_START = 4;
    /**
     * Requests finished per wave: this many, or one per slot. Every decision takes two waves, so a
     * limit of 8 is judged on 16 answers; more would leave a thrashing server alone for minutes.
     */
    static final int MIN_WAVE = 4;
    /** A step down that gives this much more is followed by another at once. */
    static final double CLEAR_GAIN = 1.2;
    /**
     * Without /metrics, one more is kept only if it brings at least this much more throughput; a
     * larger step must bring this much for every request it adds, so noise alone does not keep a
     * doubling.
     */
    static final double STEP_GAIN = 1.02;
    /** Throughput below this share of the best seen means overshoot or someone else on the server. */
    static final double DROP = 0.75;
    /** Waves without growth after a step back, so the limit does not swing between two values. */
    static final int HOLD_WAVES = 8;
    /** Waves without a change after which the limit counts as found. */
    static final int SETTLED_WAVES = 3;
    private static final double ALPHA = 0.5;

    /** What a reading of the server's own state allows. */
    public enum Signal {
        /** No reading (no /metrics, or --swarm-blind): throughput alone decides. */
        NONE,
        /** /metrics reads green: one more is safe. */
        GREEN,
        /** /metrics reads yellow or red: no growth. */
        HOLD
    }

    private final int ceiling;
    private int limit;
    private Signal signal = Signal.NONE;
    private int epoch;
    private boolean frozen;
    /** Started above the blind default on a ceiling told: see first whether fewer do as well. */
    private boolean downFirst;
    /**
     * Started on a learned limit: if one more does not pay, try one step fewer at once rather than
     * after a quiet spell, in case the server changed since it was learned.
     */
    private boolean checkBelow;
    /** Doubling rather than adding one: no congestion seen yet. */
    private boolean slowStart;
    /** The limit before the last doubling, which showed no congestion; 0 when not doubled yet. */
    private int beforeDoubling;
    /** Requests the server's own reading says still fit, or {@link Integer#MAX_VALUE} if it says nothing. */
    private int room = Integer.MAX_VALUE;
    /** Unsaturated answers in a row, and the most in flight among them. */
    private int idleSamples;
    private double idlePeak;
    /** The limit was lowered to what the swarm asked for, not learned from the server. */
    private boolean demandBound;
    private int loadedLimit;
    private int hold;
    /** Waves before the next step down is tried, in the same way as {@link #hold} for growth. */
    private int downHold;
    /** The limit a step on trial came from, and what it gave; 0 when no step is on trial. */
    private int from;
    private double fromThroughput;
    private int quietWaves;
    /**
     * Waves to discard: the first, whose answers include the prefill of prompts no one sent before,
     * and the one after a cut, while requests from the wider limit still finish.
     */
    private int skip = 1;
    private final Map<Integer, Double> throughput = new HashMap<>();
    private final Map<Integer, Integer> waves = new HashMap<>();
    private double best;
    private int waveSamples;
    private double waveTokens;
    /** Sum of seconds / concurrency: the server time the wave's requests stand for. */
    private double waveServerSeconds;
    /** Whether anyone was held back during the wave: growth is only worth trying then. */
    private boolean waveWanted;

    public ConcurrencyLimiter(int ceiling, int start) {
        this(ceiling, start, false);
    }

    /**
     * @param learned whether {@code start} is the limit learned last time under load: then it is
     *                neither searched upwards by doubling nor first tried lower
     */
    public ConcurrencyLimiter(int ceiling, int start, boolean learned) {
        this.ceiling = Math.max(1, ceiling);
        this.limit = Math.max(1, Math.min(start, this.ceiling));
        this.downFirst = !learned && this.limit > BLIND_START;
        this.slowStart = !learned;
        this.checkBelow = learned;
    }

    public int limit() { return limit; }
    public int ceiling() { return ceiling; }
    /** Whether the limit has held for a few waves, rather than still moving. */
    public boolean settled() { return quietWaves >= SETTLED_WAVES || limit == ceiling && ceiling == 1; }
    /** The limit last measured while requests waited for it: what the next run starts from, 0 if none. */
    public int loadedLimit() { return loadedLimit; }

    /** Whether the limit is only what the swarm asked for lately, not what the server takes. */
    public boolean demandBound() { return demandBound; }
    /** Changes with every new limit. */
    public int epoch() { return epoch; }

    public void signal(Signal s) { signal = s; }

    /**
     * What the server's reading says still fits - for example in-flight requests times the share of
     * its KV cache left - so a doubling does not overrun it at once; {@link Integer#MAX_VALUE}
     * when it says nothing. Only bounds a doubling: one more is always allowed on green.
     */
    public void room(int requests) { room = Math.max(1, requests); }

    /** Whether the limit still doubles: no congestion seen since the start. */
    public boolean slowStart() { return slowStart; }

    void freeze() { frozen = true; }

    /**
     * One finished request.
     *
     * @param seconds     from sending it to the last token
     * @param tokens      generated tokens plus {@link #PREFILL_WEIGHT} x uncached prompt tokens
     * @param concurrency requests in flight on average while it ran, itself included
     * @param saturated   whether the limit was in use while it ran: full, or others held back
     * @param wanted      whether others were held back: only then would a wider limit be used
     */
    public void onSample(double seconds, double tokens, double concurrency, boolean saturated, boolean wanted) {
        if (seconds <= 0 || tokens <= 0 || concurrency <= 0) return;
        if (!saturated) {
            // The swarm did not fill the limit, so this says nothing about it. A limit far above
            // what the swarm asks for would never be tested, so it comes down to the demand.
            idleSamples++;
            idlePeak = Math.max(idlePeak, concurrency);
            if (idleSamples >= waveSize()) {
                int demand = Math.max(1, (int) Math.ceil(idlePeak));
                idleSamples = 0;
                idlePeak = 0;
                if (demand < limit) {
                    demandBound = true;
                    // A step on trial past the demand stays on trial, at the demand.
                    if (demand <= from) from = 0;
                    moveTo(demand);
                }
            }
            return;
        }
        idleSamples = 0;
        idlePeak = 0;
        demandBound = false;
        waveWanted |= wanted;
        waveTokens += tokens;
        waveServerSeconds += seconds / concurrency;
        waveSamples++;
        if (waveSamples < waveSize()) return;
        double t = waveTokens / waveServerSeconds;
        boolean more = waveWanted;
        resetWave();
        onWave(t, more);
    }

    private int waveSize() {
        return Math.max(MIN_WAVE, limit);
    }

    private void resetWave() {
        waveWanted = false;
        waveTokens = 0;
        waveServerSeconds = 0;
        waveSamples = 0;
    }

    private void onWave(double t, boolean wanted) {
        if (wanted && from == 0) loadedLimit = limit;
        if (skip > 0) {
            skip--;
            // On green growth needs no measurement, so a cold start does not wait out the first wave.
            if (slowStart && signal == Signal.GREEN && wanted && canGrow()) grow();
            return;
        }
        if (hold > 0) hold--;
        if (downHold > 0) downHold--;
        int n = waves.merge(limit, 1, Integer::sum);
        double mean = throughput.merge(limit, t, (old, x) -> old + ALPHA * (x - old));
        if (from > 0) {
            // A step on trial: kept if it paid (up) or cost nothing (down), else undone.
            if (n < 2) return;
            boolean up = limit > from;
            boolean keep = up ? mean >= Math.pow(STEP_GAIN, limit - from) * fromThroughput
                    : mean * STEP_GAIN >= fromThroughput;
            int back = from;
            double was = fromThroughput;
            from = 0;
            best = Math.max(best, mean);
            if (up) checkBelow &= !keep;
            if (!keep) {
                if (up) hold = HOLD_WAVES; else downHold = HOLD_WAVES;
                slowStart = false;  // one more did not pay: this is about where the server peaks
                moveTo(back);
                if (up && checkBelow && limit > 1) {
                    checkBelow = false;
                    stepDown();
                }
            } else if (!up && mean >= CLEAR_GAIN * was && limit > 1) {
                // Far better with fewer: the server was thrashing. Keep going down.
                stepDown();
            }
            return;
        }
        if (signal == Signal.NONE && n >= 2 && mean < DROP * best && limit > 1) {
            // Only blind: with /metrics, a busy server shows there, and a fall in throughput alone
            // is more often answers of another size.
            // Well below what was seen: the server is thrashing, or someone else is on it.
            // What was measured before no longer holds.
            throughput.clear();
            waves.clear();
            best = 0;
            hold = HOLD_WAVES;
            slowStart = false;
            moveTo(limit - 1);
            return;
        }
        if (n >= 2) best = Math.max(best, mean);  // one wave is too noisy to set the bar
        if (downFirst && signal == Signal.NONE && limit > 1 && downHold == 0 && !measuredBelow()) {
            // Started high on what was known: first see whether fewer do as well.
            downFirst = false;
            stepDown();
            return;
        }
        if (wanted && canGrow()) {
            grow();
            return;
        }
        if (signal == Signal.NONE && limit > 1 && downHold == 0 && quietWaves >= 2) {
            // Nothing tells when the server is past its best; one step down shows it. A server
            // with fixed slots gives the same throughput with fewer, and a thrashing cache more.
            stepDown();
            return;
        }
        quietWaves++;
    }

    /** With a green light a step up is safe and kept; without one it is on trial. */
    private void grow() {
        if (signal != Signal.GREEN) trial();
        int to = limit + 1;
        if (slowStart) {
            beforeDoubling = limit;
            to = Math.max(to, Math.min(2 * limit, room));
        }
        moveTo(to);
    }

    private void stepDown() {
        slowStart = false;
        trial();
        moveTo(limit - Math.max(1, limit / 4));
    }

    private boolean measuredBelow() {
        for (int l : throughput.keySet()) if (l < limit) return true;
        return false;
    }

    private void trial() {
        from = limit;
        fromThroughput = throughput.get(limit);
    }

    private boolean canGrow() {
        return hold == 0 && signal != Signal.HOLD && limit < ceiling;
    }

    /**
     * Requests queue in the server: one less, and no growth for a while. Right after a doubling,
     * back to the limit before it, which did not queue; from then on the limit grows by one.
     */
    public void backOff() {
        hold = HOLD_WAVES;
        from = 0;
        int to = slowStart && beforeDoubling > 0 ? beforeDoubling : limit - 1;
        slowStart = false;
        moveTo(to);
    }

    /** 429, 503, a timeout or a red reading: halve now. */
    public void overload() {
        hold = HOLD_WAVES;
        from = 0;
        skip = 1;
        throughput.clear();
        waves.clear();
        best = 0;
        slowStart = false;
        moveTo(Math.max(1, limit / 2));
    }

    private void moveTo(int newLimit) {
        if (frozen) return;
        int to = Math.max(1, Math.min(newLimit, ceiling));
        if (to == limit) return;
        limit = to;
        epoch++;
        quietWaves = 0;
        resetWave();
    }
}
