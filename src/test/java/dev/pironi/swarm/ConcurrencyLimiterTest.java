package dev.pironi.swarm;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The limiter against a simulated server on a virtual clock: {@code slots} requests run at once,
 * the rest wait in the server's queue; prompts are prefilled one at a time; decode batches, so
 * every running request slows as more run but the total grows (as b^0.6). A swarm of agents
 * always has a next request ready.
 */
class ConcurrencyLimiterTest {
    private static final double DT = 0.005;

    /** @param limit the limit held longest over the second half; a probe may be on at the end */
    record Result(int limit, boolean settled, double tokensPerSecond) {
    }

    private static final class Request {
        final DispatchController.Permit permit;
        double prefillLeft;
        double decodeLeft;
        final long generated;

        Request(DispatchController.Permit permit, long uncached, long generated) {
            this.permit = permit;
            this.prefillLeft = uncached;
            this.decodeLeft = generated;
            this.generated = generated;
        }
    }

    /** Runs {@code seconds} of virtual time; throughput is measured over the second half. */
    static Result simulate(int slots, double prefillRate, double decodeAlone, int ceiling, int agents,
                           double seconds, long seed) {
        AtomicLong clock = new AtomicLong();
        DispatchController c = new DispatchController(ceiling, 1, 1_000_000, clock::get);
        Random random = new Random(seed);
        ArrayDeque<Request> server = new ArrayDeque<>();
        int waiting = agents;
        double generatedLate = 0;
        java.util.Map<Integer, Long> held = new java.util.HashMap<>();
        long steps = (long) (seconds / DT);
        for (long step = 0; step < steps; step++) {
            while (waiting > 0) {
                long uncached = 500 + random.nextInt(3_500);
                DispatchController.Permit p = c.tryAcquire(uncached);
                if (p == null) break;
                server.add(new Request(p, uncached, 150 + random.nextInt(450)));
                waiting--;
            }
            // Prefill: the running requests' prompts, in order, share one prefill engine.
            double prefill = prefillRate * DT;
            int running = 0;
            List<Request> decoding = new ArrayList<>();
            for (Request r : server) {
                if (running++ >= slots) break;
                if (r.prefillLeft > 0) {
                    double used = Math.min(prefill, r.prefillLeft);
                    r.prefillLeft -= used;
                    prefill -= used;
                } else {
                    decoding.add(r);
                }
            }
            double each = decodeAlone * Math.pow(Math.max(1, decoding.size()), -0.4) * DT;
            for (Request r : decoding) r.decodeLeft -= each;
            clock.addAndGet((long) (DT * 1e9));
            if (step > steps / 2) held.merge(c.window(), 1L, Long::sum);
            for (Iterator<Request> it = server.iterator(); it.hasNext(); ) {
                Request r = it.next();
                if (r.prefillLeft <= 0 && r.decodeLeft <= 0) {
                    it.remove();
                    c.release(r.permit, r.generated);
                    if (step > steps / 2) generatedLate += r.generated;
                    waiting++;
                }
            }
        }
        int mostHeld = held.entrySet().stream().max(java.util.Map.Entry.comparingByValue()).orElseThrow().getKey();
        return new Result(mostHeld, c.parallelism().settled(), generatedLate / (seconds / 2));
    }

    @Test
    void oneSlotSettlesAtOne() {
        Result r = simulate(1, 6_000, 60, 64, 24, 1_500, 1);
        assertEquals(1, r.limit(), r.toString());
    }

    @Test
    void fourSlotsSettleAtFour() {
        Result r = simulate(4, 6_000, 60, 64, 24, 1_500, 2);
        assertTrue(r.limit() >= 3 && r.limit() <= 5, r.toString());
    }

    @Test
    void eightSlotsSettleAtEight() {
        Result r = simulate(8, 6_000, 60, 64, 24, 1_500, 3);
        assertTrue(r.limit() >= 6 && r.limit() <= 9, r.toString());
    }

    @Test
    void atOneSlotTheSearchCostsNothingAgainstWorkingInSequence() {
        Result learned = simulate(1, 6_000, 60, 64, 24, 1_500, 4);
        Result sequential = simulate(1, 6_000, 60, 1, 24, 1_500, 4);
        assertTrue(learned.tokensPerSecond() >= 0.95 * sequential.tokensPerSecond(),
                learned + " vs " + sequential);
    }

    @Test
    void withSlowPrefillTheLimitStillMatchesTheBestFixedOne() {
        // 300 prompt tokens/s: prefill, not the slots, is what the server runs out of.
        double best = 0;
        for (int fixed : new int[]{1, 2, 4, 8}) {
            best = Math.max(best, simulate(8, 300, 30, fixed, 24, 3_000, 5).tokensPerSecond());
        }
        Result learned = simulate(8, 300, 30, 64, 24, 3_000, 5);
        assertTrue(learned.tokensPerSecond() >= 0.85 * best, learned + " vs best " + best);
    }

    @Test
    void withFastPrefillEightSlotsAreFound() {
        Result r = simulate(8, 20_000, 120, 64, 24, 1_000, 6);
        assertTrue(r.limit() >= 6 && r.limit() <= 9, r.toString());
    }

    @Test
    void aCeilingFromTheUserOrTheServerIsNeverPassed() {
        Result r = simulate(8, 6_000, 60, 3, 24, 1_000, 7);
        assertEquals(3, r.limit(), r.toString());
    }

    @Test
    void aLimiterWithoutDemandStaysAtOne() {
        Result r = simulate(8, 6_000, 60, 64, 1, 600, 8);
        assertEquals(1, r.limit(), "one agent alone has nothing to learn from: " + r);
    }
}
