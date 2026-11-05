package dev.pironi.swarm;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The limiter against simulated servers on a virtual clock. A swarm of agents always has a next
 * request ready. Two kinds of server:
 * <ul>
 *   <li>fixed slots (llama.cpp, Ollama, LM Studio): {@code slots} requests run at once, the rest
 *       wait in the server's own queue;</li>
 *   <li>vLLM-like: no fixed slots, but a KV cache of {@code kvTokens}. A running request holds its
 *       prompt and what it generated so far; when they no longer fit, the newest request is
 *       evicted and later prefilled again from scratch (a preemption), so too many in flight makes
 *       the server slower, not just busier.</li>
 * </ul>
 * Prompts are prefilled one at a time; decode batches, so every running request slows as more run
 * but the total grows (as b^0.6).
 *
 * <p>With {@link Options#metrics} the controller also gets a /metrics-like reading every
 * {@link #TICK} seconds: KV use, requests waiting, preemptions and the mean time the requests first
 * scheduled during the interval spent queued - from arriving at the server until their prefill
 * started, as vLLM measures it - so a request behind long prompts queues for their prefill.
 */
class ConcurrencyLimiterTest {
    private static final double DT = 0.005;

    /** A server to simulate; {@code kvTokens} 0 means fixed slots. */
    record Server(int slots, double prefillRate, double decodeAlone, long kvTokens, int minPrompt, int maxPrompt,
                  int minGen, int maxGen, int thrashAbove) {
        Server(int slots, double prefillRate, double decodeAlone, long kvTokens, int minPrompt, int maxPrompt,
               int minGen, int maxGen) {
            this(slots, prefillRate, decodeAlone, kvTokens, minPrompt, maxPrompt, minGen, maxGen, 0);
        }

        static Server slots(int slots, double prefillRate, double decodeAlone) {
            return new Server(slots, prefillRate, decodeAlone, 0, 500, 4_000, 150, 600);
        }
    }

    /**
     * @param limit         the limit held longest over the second half
     * @param reds          readings the controller took as red (only with /metrics)
     * @param learned       what would be kept for the next run, at the end
     * @param drainLimit    the limit when the run began to drain: an agent is done and no more are
     *                      left than the limit, so no one is held back again; 0 if it never did
     * @param drainLearned  what would have been kept at that moment
     * @param drainReds     red readings after it
     * @param lowestBeforeDrain the lowest limit before it (or over the whole run)
     */
    record Result(int limit, double tokensPerSecond, long preemptions, int reds, int learned, int drainLimit,
                  int drainLearned, int drainReds, int lowestBeforeDrain) {
    }

    /**
     * @param metrics          feed the controller /metrics-like readings
     * @param requestsPerAgent each agent stops after this many requests, so the run drains; 0 never
     * @param freshPrompts     every request is a new prompt, nothing of it cached (scenario U)
     * @param learned          the start is a limit learned last time
     */
    record Options(boolean metrics, int requestsPerAgent, boolean freshPrompts, boolean learned) {
        static final Options BLIND = new Options(false, 0, false, false);
        static final Options LEARNED = new Options(false, 0, false, true);
    }

    private static final double TICK = 1.5;
    static boolean TRACE = Boolean.getBoolean("sim.trace");

    private static final class Request {
        final DispatchController.Permit permit;
        final int agent;
        final long prompt;
        final long generated;
        double prefillLeft;
        double decodeLeft;
        /** When it reached the server, in seconds; -1 once it was first scheduled. */
        double arrived;

        Request(DispatchController.Permit permit, int agent, long prompt, long uncached, long generated, double arrived) {
            this.permit = permit;
            this.arrived = arrived;
            this.agent = agent;
            this.prompt = prompt;
            this.generated = generated;
            this.prefillLeft = uncached;
            this.decodeLeft = generated;
        }
    }

    /** What an agent's next turn adds to its context besides the answer: tool output, a new message. */
    private static final int TURN_INPUT = 1_500;
    private static final int TURNS = 6;

    /**
     * Runs {@code seconds} of virtual time; throughput is measured over the second half. On a KV
     * server each agent's conversation grows turn by turn and its start is served from a prefix cache
     * that gets only what the running requests leave free: with too many running, an agent's earlier
     * turns are evicted and its next turn is prefilled whole.
     */
    static Result simulate(Server server, int ceiling, int start, int agents, double seconds, long seed) {
        return simulate(server, ceiling, start, agents, seconds, seed, Options.BLIND);
    }

    static Result simulate(Server server, int ceiling, int start, int agents, double seconds, long seed,
                           Options options) {
        AtomicLong clock = new AtomicLong();
        DispatchController c = new DispatchController(ceiling, start, options.learned(), 1_000_000, clock::get);
        if (ceiling == start && FIXED.get()) c.fixLimit();
        Random random = new Random(seed);
        boolean kvServer = server.kvTokens() > 0;
        List<Request> running = new ArrayList<>();
        ArrayDeque<Request> queued = new ArrayDeque<>();
        ArrayDeque<Integer> ready = new ArrayDeque<>();
        long[] context = new long[agents];
        int[] turn = new int[agents];
        for (int a = 0; a < agents; a++) {
            ready.add(a);
            context[a] = server.minPrompt() + random.nextInt(server.maxPrompt() - server.minPrompt());
        }
        // Agent -> tokens of its conversation in the prefix cache, least recently used first.
        LinkedHashMap<Integer, Long> cache = new LinkedHashMap<>(16, 0.75f, true);
        double generatedLate = 0;
        long preemptions = 0;
        Map<Integer, Long> held = new HashMap<>();
        int[] sent = new int[agents];
        int active = agents;
        int reds = 0;
        int drainLimit = 0;
        int drainLearned = 0;
        int drainReds = 0;
        int lowest = c.window();
        int lastTraced = -1;
        long tickPreemptions = 0;
        double tickQueue = 0;
        int tickScheduled = 0;
        long stepsPerTick = Math.round(TICK / DT);
        long steps = (long) (seconds / DT);
        for (long step = 0; step < steps; step++) {
            double now = step * DT;
            while (!ready.isEmpty()) {
                int a = ready.peekFirst();
                long prompt = kvServer ? context[a]
                        : server.minPrompt() + random.nextInt(server.maxPrompt() - server.minPrompt());
                long uncached = kvServer ? prompt - Math.min(prompt, cache.getOrDefault(a, 0L)) : prompt;
                DispatchController.Permit p = c.tryAcquire(uncached);
                if (p == null) break;
                ready.pollFirst();
                sent[a]++;
                queued.add(new Request(p, a, prompt, uncached,
                        server.minGen() + random.nextInt(server.maxGen() - server.minGen()), now));
            }
            if (options.requestsPerAgent() > 0 && active == 0 && running.isEmpty() && queued.isEmpty()) break;
            // Scheduling: fixed slots take the queue in order; a KV server takes a request while its
            // prompt fits, and evicts the newest when the running ones outgrow the cache.
            while (!queued.isEmpty() && running.size() < server.slots()
                    && (!kvServer || kvUsed(running) + queued.peekFirst().prompt <= server.kvTokens())) {
                Request r = queued.pollFirst();
                if (kvServer && r.prefillLeft < r.prompt && !cache.containsKey(r.agent)) {
                    r.prefillLeft = r.prompt;  // its cached start was evicted while it waited
                }
                cache.remove(r.agent);  // now held by the running request itself
                running.add(r);
            }
            while (kvServer && running.size() > 1 && kvUsed(running) > server.kvTokens()) {
                // The one that holds the most: the costliest to lose, as a cache under pressure
                // tends to lose its long-running work.
                Request evicted = running.stream().max(java.util.Comparator.comparingDouble(
                        r -> r.prompt + r.generated - r.decodeLeft)).orElseThrow();
                running.remove(evicted);
                // Recomputed from scratch later: the prompt and what it generated so far.
                evicted.prefillLeft = evicted.prompt + (evicted.generated - evicted.decodeLeft);
                queued.addFirst(evicted);
                preemptions++;
                tickPreemptions++;
            }
            if (kvServer) {
                double free = server.kvTokens() - kvUsed(running);
                long cached = 0;
                for (long t : cache.values()) cached += t;
                for (var it = cache.entrySet().iterator(); it.hasNext() && cached > free; ) {
                    cached -= it.next().getValue();
                    it.remove();
                }
            }
            double prefill = server.prefillRate() * DT;
            List<Request> decoding = new ArrayList<>();
            for (Request r : running) {
                if (r.arrived >= 0 && (prefill > 0 || r.prefillLeft <= 0)) {
                    // First scheduled: its prefill starts now, or it had none.
                    tickQueue += now - r.arrived;
                    tickScheduled++;
                    r.arrived = -1;
                }
                if (r.prefillLeft > 0) {
                    double used = Math.min(prefill, r.prefillLeft);
                    r.prefillLeft -= used;
                    prefill -= used;
                } else {
                    decoding.add(r);
                }
            }
            double each = server.decodeAlone() * Math.pow(Math.max(1, decoding.size()), -0.4) * DT;
            if (server.thrashAbove() > 0 && decoding.size() > server.thrashAbove()) {
                // Past this the server swaps its cache in and out: the whole batch slows sharply.
                each *= Math.pow((double) server.thrashAbove() / decoding.size(), 2);
            }
            for (Request r : decoding) r.decodeLeft -= each;
            clock.addAndGet((long) (DT * 1e9));
            if (step > steps / 2) held.merge(c.window(), 1L, Long::sum);
            if (options.metrics() && step % stepsPerTick == stepsPerTick - 1) {
                int waiting = queued.size();
                for (Request r : running) if (r.arrived >= 0) waiting++;
                double kv = kvServer ? kvUsed(running) / server.kvTokens() : 0;
                DispatchController.Light light = c.onTick(new Sensors(true, kv, waiting, tickPreemptions,
                        tickScheduled > 0 ? tickQueue / tickScheduled : Double.NaN, Double.NaN,
                        server.prefillRate(), false));
                if (light == DispatchController.Light.RED) {
                    reds++;
                    if (drainLimit > 0) drainReds++;
                }
                tickPreemptions = 0;
                tickQueue = 0;
                tickScheduled = 0;
            }
            for (Iterator<Request> it = running.iterator(); it.hasNext(); ) {
                Request r = it.next();
                if (r.prefillLeft <= 0 && r.decodeLeft <= 0) {
                    it.remove();
                    c.release(r.permit, r.generated);
                    if (step > steps / 2) generatedLate += r.generated;
                    int a = r.agent;
                    if (options.freshPrompts() || ++turn[a] % TURNS == 0) {
                        context[a] = server.minPrompt() + random.nextInt(server.maxPrompt() - server.minPrompt());
                    } else {
                        cache.put(a, r.prompt + r.generated);
                        context[a] = r.prompt + r.generated + TURN_INPUT;
                    }
                    if (options.requestsPerAgent() > 0 && sent[a] >= options.requestsPerAgent()) {
                        active--;
                    } else {
                        ready.add(a);
                    }
                }
            }
            if (drainLimit == 0) lowest = Math.min(lowest, c.window());
            if (TRACE && c.window() != lastTraced) {
                System.out.printf("t=%.0f W=%d%n", now, c.window());
                lastTraced = c.window();
            }
            if (options.requestsPerAgent() > 0 && drainLimit == 0 && active < agents && active <= c.window()) {
                // From here no one is held back again: whatever happens next is the run draining.
                drainLimit = c.window();
                drainLearned = c.learnedLimit();
            }
        }
        int mostHeld = held.isEmpty() ? c.window()
                : held.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
        return new Result(mostHeld, generatedLate / (seconds / 2), preemptions, reds, c.learnedLimit(), drainLimit,
                drainLearned, drainReds, lowest);
    }

    private static double kvUsed(List<Request> running) {
        double used = 0;
        for (Request r : running) used += r.prompt + (r.generated - r.decodeLeft);
        return used;
    }

    /** Whether a run with start = ceiling holds the limit there: a fixed limit to compare with. */
    private static final ThreadLocal<Boolean> FIXED = ThreadLocal.withInitial(() -> false);

    static Result fixed(Server server, int limit, int agents, double seconds, long seed) {
        FIXED.set(true);
        try {
            return simulate(server, limit, limit, agents, seconds, seed);
        } finally {
            FIXED.set(false);
        }
    }

    private static double bestFixed(Server server, int agents, double seconds, long seed, int... limits) {
        double best = 0;
        for (int limit : limits) best = Math.max(best, fixed(server, limit, agents, seconds, seed).tokensPerSecond());
        return best;
    }

    /** Learned throughput over the best fixed limit, for eight runs with different requests, sorted. */
    private static double[] shares(Server server, int start, double seconds) {
        double[] out = new double[8];
        for (int seed = 0; seed < out.length; seed++) {
            double best = bestFixed(server, 24, seconds, 100 + seed, 1, 2, 4, 6, 8, 12);
            out[seed] = simulate(server, 64, start, 24, seconds, 100 + seed).tokensPerSecond() / best;
        }
        Arrays.sort(out);
        return out;
    }

    @Test
    void onFixedSlotsTheLearnedLimitIsAsGoodAsTheBestFixedOne() {
        for (int slots : new int[]{1, 2, 4, 8}) {
            double[] s = shares(Server.slots(slots, 6_000, 60), ConcurrencyLimiter.BLIND_START, 1_500);
            assertTrue(s[0] >= 0.9 && s[s.length / 2] >= 0.95, slots + " slots: " + Arrays.toString(s));
        }
    }

    @Test
    void eightSlotsAreFoundFromABlindStartAtFour() {
        int[] limits = new int[8];
        for (int seed = 0; seed < limits.length; seed++) {
            limits[seed] = simulate(Server.slots(8, 6_000, 60), 64, 4, 24, 1_500, 100 + seed).limit();
        }
        Arrays.sort(limits);
        assertTrue(limits[0] >= 7, Arrays.toString(limits));
    }

    @Test
    void aServerThatToldItsSlotsIsNeverPassed() {
        // /props said 3: start and ceiling are both 3.
        Result r = simulate(Server.slots(8, 6_000, 60), 3, 3, 24, 1_000, 7);
        assertEquals(3, r.limit(), r.toString());
    }

    @Test
    void withSlowPrefillTheLimitStillMatchesTheBestFixedOne() {
        // 300 prompt tokens/s: prefill, not the slots, is what the server runs out of.
        Server slow = Server.slots(8, 300, 30);
        double best = bestFixed(slow, 24, 3_000, 5, 1, 2, 4, 8);
        Result learned = simulate(slow, 64, 4, 24, 3_000, 5);
        assertTrue(learned.tokensPerSecond() >= 0.85 * best, learned + " vs best " + best);
    }

    /**
     * vLLM-like: no slot limit to speak of, a KV cache for about eight of these conversations, slow
     * prefill. Past it requests are evicted and recomputed, and agents lose their cached turns.
     */
    private static final Server KV_SERVER = new Server(32, 2_000, 30, 100_000, 4_000, 12_000, 300, 900);

    @Test
    void onAKvCacheServerAStaleHighStartCostsLittle() {
        // A stale profile says 16. On this server the best fixed limit is 10-12 and 16 is a few
        // per cent worse; the limiter must not do worse than 16 held, nor much worse than the best.
        double best = bestFixed(KV_SERVER, 24, 3_000, 9, 4, 6, 8, 10, 12);
        Result held = fixed(KV_SERVER, 16, 24, 3_000, 9);
        Result learned = simulate(KV_SERVER, 64, 16, 24, 3_000, 9, Options.LEARNED);
        assertTrue(learned.tokensPerSecond() >= 0.9 * best && learned.tokensPerSecond() >= 0.97 * held.tokensPerSecond(),
                learned + " vs best fixed " + best + ", 16 held " + held);
    }

    @Test
    void onAServerThatThrashesTheLimitComesDown() {
        // Past six running the whole batch slows sharply, as a vLLM out of KV cache does. A stale
        // profile says 16.
        Server thrash = new Server(32, 6_000, 60, 0, 500, 4_000, 150, 600, 6);
        double best = bestFixed(thrash, 24, 1_500, 9, 2, 4, 6, 8);
        Result held = fixed(thrash, 16, 24, 1_500, 9);
        Result learned = simulate(thrash, 64, 16, 24, 1_500, 9, Options.LEARNED);
        assertTrue(learned.limit() <= 8 && learned.tokensPerSecond() >= 0.85 * best,
                learned + " vs best fixed " + best + ", 16 held " + held);
    }

    @Test
    void onAServerThatThrashesAColdStartComesDownToo() {
        // Doubling from 4 overshoots to 8 past the collapse at six; the step must not be kept.
        Server thrash = new Server(32, 6_000, 60, 0, 500, 4_000, 150, 600, 6);
        double best = bestFixed(thrash, 24, 1_500, 9, 2, 4, 6, 8);
        Result learned = simulate(thrash, 64, ConcurrencyLimiter.BLIND_START, 24, 1_500, 9);
        assertTrue(learned.limit() <= 8 && learned.tokensPerSecond() >= 0.85 * best, learned + " vs best fixed " + best);
    }

    /**
     * vLLM as measured on qwen36: it runs at most eight at once (max_num_seqs) and queues the rest
     * itself; the KV cache has room for far more. Agents' conversations grow and stay cached.
     */
    static final Server VLLM_8_SEQS = new Server(8, 4_000, 60, 2_000_000, 1_000, 3_000, 300, 900);

    /** The same server's prefill, but each request a fresh 13-14.5k-token prompt, as qwen38 in scenario U. */
    static final Server LONG_PREFILL = new Server(8, 1_500, 15, 10_000_000, 13_000, 14_500, 300, 900);

    @Test
    void withMetricsAServerThatQueuesPastEightIsUsedAsWellAsAtTheBestFixedLimit() {
        double[] out = new double[8];
        for (int seed = 0; seed < out.length; seed++) {
            double best = bestFixed(VLLM_8_SEQS, 24, 1_500, 100 + seed, 4, 6, 8, 10, 12, 16);
            Options metrics = new Options(true, 0, false, false);
            out[seed] = simulate(VLLM_8_SEQS, 64, ConcurrencyLimiter.BLIND_START, 24, 1_500, 100 + seed, metrics)
                    .tokensPerSecond() / best;
        }
        Arrays.sort(out);
        assertTrue(out[0] >= 0.9 && out[out.length / 2] >= 0.95, Arrays.toString(out));
    }

    @Test
    void theEndOfARunDoesNotLowerTheLimitOrWhatIsLearned() {
        // 16 agents, 6 requests each, then the run drains: requests that queued inside the server
        // (with a limit above its eight) report their long queue times while fewer and fewer run.
        for (Options start : new Options[]{new Options(true, 6, false, false), new Options(true, 6, false, true)}) {
            for (int seed = 0; seed < 4; seed++) {
                int from = start.learned() ? 12 : ConcurrencyLimiter.BLIND_START;
                Result r = simulate(VLLM_8_SEQS, 64, from, 16, 3_000, 200 + seed, start);
                assertTrue(r.drainLimit() > 0, "never drained: " + r);
                assertEquals(0, r.drainReds(), "red while draining: " + r);
                assertTrue(r.learned() >= r.drainLearned(), "the drain lowered what is kept: " + r);
            }
        }
    }

    @Test
    void longUncachedPromptsQueueingForPrefillAreNotAnOverload() {
        // 12 agents x 2 turns, each a fresh 13-14.5k-token prompt at 1.5k tok/s of prefill: the
        // last of a burst queues for the others' prefill, ~9 s each, with the KV cache nearly empty.
        for (int seed = 0; seed < 4; seed++) {
            Result r = simulate(LONG_PREFILL, 64, ConcurrencyLimiter.BLIND_START, 12, 3_000, 300 + seed,
                    new Options(true, 2, true, false));
            assertEquals(0, r.reds(), "prefill read as red: " + r);
            assertTrue(r.lowestBeforeDrain() >= ConcurrencyLimiter.BLIND_START, "stepped back: " + r);
        }
    }

    @Test
    void aLimiterWithoutDemandFollowsTheDemand() {
        // One agent: a limit of 4 would never be tested, so it comes down to what is asked for.
        Result r = simulate(Server.slots(8, 6_000, 60), 64, 4, 1, 600, 8);
        assertEquals(1, r.limit(), r.toString());
    }
}
