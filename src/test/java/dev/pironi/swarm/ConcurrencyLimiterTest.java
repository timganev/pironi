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
 */
class ConcurrencyLimiterTest {
    private static final double DT = 0.005;

    /** A server to simulate; {@code kvTokens} 0 means fixed slots. */
    record Server(int slots, double prefillRate, double decodeAlone, long kvTokens, int minPrompt, int maxPrompt,
                  int minGen, int maxGen) {
        static Server slots(int slots, double prefillRate, double decodeAlone) {
            return new Server(slots, prefillRate, decodeAlone, 0, 500, 4_000, 150, 600);
        }
    }

    /** @param limit the limit held longest over the second half */
    record Result(int limit, double tokensPerSecond, long preemptions) {
    }

    private static final class Request {
        final DispatchController.Permit permit;
        final int agent;
        final long prompt;
        final long generated;
        double prefillLeft;
        double decodeLeft;

        Request(DispatchController.Permit permit, int agent, long prompt, long uncached, long generated) {
            this.permit = permit;
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
        AtomicLong clock = new AtomicLong();
        DispatchController c = new DispatchController(ceiling, start, 1_000_000, clock::get);
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
        long steps = (long) (seconds / DT);
        for (long step = 0; step < steps; step++) {
            while (!ready.isEmpty()) {
                int a = ready.peekFirst();
                long prompt = kvServer ? context[a]
                        : server.minPrompt() + random.nextInt(server.maxPrompt() - server.minPrompt());
                long uncached = kvServer ? prompt - Math.min(prompt, cache.getOrDefault(a, 0L)) : prompt;
                DispatchController.Permit p = c.tryAcquire(uncached);
                if (p == null) break;
                ready.pollFirst();
                queued.add(new Request(p, a, prompt, uncached,
                        server.minGen() + random.nextInt(server.maxGen() - server.minGen())));
            }
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
                if (r.prefillLeft > 0) {
                    double used = Math.min(prefill, r.prefillLeft);
                    r.prefillLeft -= used;
                    prefill -= used;
                } else {
                    decoding.add(r);
                }
            }
            double each = server.decodeAlone() * Math.pow(Math.max(1, decoding.size()), -0.4) * DT;
            for (Request r : decoding) r.decodeLeft -= each;
            clock.addAndGet((long) (DT * 1e9));
            if (step > steps / 2) held.merge(c.window(), 1L, Long::sum);
            for (Iterator<Request> it = running.iterator(); it.hasNext(); ) {
                Request r = it.next();
                if (r.prefillLeft <= 0 && r.decodeLeft <= 0) {
                    it.remove();
                    c.release(r.permit, r.generated);
                    if (step > steps / 2) generatedLate += r.generated;
                    int a = r.agent;
                    if (++turn[a] % TURNS == 0) {
                        context[a] = server.minPrompt() + random.nextInt(server.maxPrompt() - server.minPrompt());
                    } else {
                        cache.put(a, r.prompt + r.generated);
                        context[a] = r.prompt + r.generated + TURN_INPUT;
                    }
                    ready.add(a);
                }
            }
        }
        int mostHeld = held.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
        return new Result(mostHeld, generatedLate / (seconds / 2), preemptions);
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
        Result learned = simulate(KV_SERVER, 64, 16, 24, 3_000, 9);
        assertTrue(learned.tokensPerSecond() >= 0.9 * best && learned.tokensPerSecond() >= 0.97 * held.tokensPerSecond(),
                learned + " vs best fixed " + best + ", 16 held " + held);
    }

    @Test
    void onAKvCacheServerTheLimitComesDownWhereMoreIsClearlyWorse() {
        // Smaller cache: 16 in flight thrash it.
        Server tight = new Server(32, 2_000, 30, 60_000, 4_000, 12_000, 300, 900);
        double best = bestFixed(tight, 24, 3_000, 9, 2, 4, 6, 8);
        Result held = fixed(tight, 16, 24, 3_000, 9);
        Result learned = simulate(tight, 64, 16, 24, 3_000, 9);
        assertTrue(learned.limit() < 16 && learned.tokensPerSecond() >= 0.9 * best,
                learned + " vs best fixed " + best + ", 16 held " + held);
    }

    @Test
    void aLimiterWithoutDemandFollowsTheDemand() {
        // One agent: a limit of 4 would never be tested, so it comes down to what is asked for.
        Result r = simulate(Server.slots(8, 6_000, 60), 64, 4, 1, 600, 8);
        assertEquals(1, r.limit(), r.toString());
    }
}
