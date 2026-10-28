package dev.pironi.swarm;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DispatchControllerTest {
    private final AtomicLong now = new AtomicLong();

    private DispatchController controller(int start, int ceiling, long budget) {
        return new DispatchController(ceiling, start, budget, now::get);
    }

    private static Sensors green() {
        return new Sensors(true, 0.2, 0, 0, 0.1, 0.9, Double.NaN, false);
    }

    private static Sensors yellow() {
        return new Sensors(true, 0.7, 0, 0, 0.1, 0.9, Double.NaN, false);
    }

    private static Sensors preempted() {
        return new Sensors(true, 0.9, 3, 2, 3.0, 0.9, Double.NaN, false);
    }

    @Test
    void theLimitStartsAtOne() {
        DispatchController c = new DispatchController(64, 16_000);
        assertEquals(1, c.window());
        assertNotNull(c.tryAcquire(10));
        assertNull(c.tryAcquire(10), "a second request went out before anything was learned");
    }

    @Test
    void theLimitBoundsRequestsInFlight() {
        DispatchController c = controller(2, 64, 1_000_000);
        assertNotNull(c.tryAcquire(10));
        assertNotNull(c.tryAcquire(10));
        assertNull(c.tryAcquire(10), "a third request went out with a limit of 2");
    }

    @Test
    void theBudgetBoundsUncachedTokensOnceMetricsAnswer() {
        DispatchController c = controller(6, 64, 10_000);
        c.onTick(green());
        assertNotNull(c.tryAcquire(8_000));
        assertNull(c.tryAcquire(4_000), "12k uncached tokens went out under a 10k budget");
        assertNotNull(c.tryAcquire(1_000), "a request that fits the budget was held back");
    }

    @Test
    void withoutMetricsTheBudgetIsNotApplied() {
        // The default prefill rate is a vLLM-on-B70 number; on another server it would be a guess.
        DispatchController c = controller(6, 64, 10_000);
        c.onTick(Sensors.unavailable(false));
        assertNotNull(c.tryAcquire(8_000));
        assertNotNull(c.tryAcquire(8_000));
    }

    @Test
    void aLoneRequestIsAdmittedHoweverLarge() {
        DispatchController c = controller(2, 64, 1_000);
        c.onTick(green());
        assertNotNull(c.tryAcquire(50_000), "one long prompt would wait forever");
    }

    @Test
    void prefilledTokensStopCountingAgainstTheBudget() {
        DispatchController c = controller(6, 64, 10_000);
        c.onTick(green());
        assertNotNull(c.tryAcquire(8_000));
        assertNull(c.tryAcquire(8_000));
        now.addAndGet(TimeUnit.SECONDS.toNanos(2)); // at the default 4k tok/s the first is prefilled
        assertNotNull(c.tryAcquire(8_000), "decode of a prefilled request blocked new prefill");
    }

    @Test
    void withoutMetricsTheLimitStillGrows() {
        // Before: an UNKNOWN light held the window where it was. Latency alone must be enough.
        DispatchController c = new DispatchController(64, 16_000, now::get);
        for (int round = 0; round < 8; round++) {
            c.onTick(Sensors.unavailable(false));
            int limit = c.window();
            var permits = new java.util.ArrayList<DispatchController.Permit>();
            for (int i = 0; i < limit; i++) permits.add(c.tryAcquire(100));
            assertNull(c.tryAcquire(100));  // demand beyond the limit
            now.addAndGet(TimeUnit.SECONDS.toNanos(1));
            for (var p : permits) c.release(p, 100);  // linear speed-up: every request as fast as alone
            if (c.window() == limit) {
                // a trial needs a few samples; repeat the round at the same limit
                for (int k = 0; k < 3; k++) {
                    permits.clear();
                    for (int i = 0; i < limit; i++) permits.add(c.tryAcquire(100));
                    c.tryAcquire(100);
                    now.addAndGet(TimeUnit.SECONDS.toNanos(1));
                    for (var p : permits) c.release(p, 100);
                }
            }
        }
        assertTrue(c.window() >= 8, "the limit stayed at " + c.window() + " with no /metrics");
    }

    @Test
    void yellowStopsTheSearchForAWiderLimit() {
        DispatchController c = controller(3, 64, 10_000);
        assertEquals(DispatchController.Light.YELLOW, c.onTick(yellow()));
        for (int i = 0; i < 10; i++) {
            var a = c.tryAcquire(1);
            var b = c.tryAcquire(1);
            var d = c.tryAcquire(1);
            c.tryAcquire(1);
            now.addAndGet(TimeUnit.SECONDS.toNanos(1));
            c.release(a, 100);
            c.release(b, 100);
            c.release(d, 100);
        }
        assertEquals(3, c.window());
        assertTrue(c.parallelism().settled());
    }

    @Test
    void redHalvesTheLimitAndCutsTheBudgetOncePerOverload() {
        DispatchController c = controller(8, 64, 16_000);
        assertEquals(DispatchController.Light.RED, c.onTick(preempted()));
        assertEquals(4, c.window());
        assertEquals(12_000, c.budget());
        c.onTick(preempted());
        c.onTick(preempted());
        c.onTick(preempted());
        assertEquals(4, c.window(), "the same overload was punished again during cooldown");
        c.onTick(preempted());
        assertEquals(2, c.window());
    }

    @Test
    void anOverloadedAnswerHalvesTheLimitOncePerLimit() {
        DispatchController c = controller(8, 64, 16_000);
        var a = c.tryAcquire(1);
        var b = c.tryAcquire(1);
        c.releaseFailed(a, true);
        assertEquals(4, c.window());
        c.releaseFailed(b, true);
        assertEquals(4, c.window(), "two answers to the same overload halved it twice");
        assertEquals(0, c.inFlight());
    }

    @Test
    void anOrdinaryFailureLeavesTheLimit() {
        DispatchController c = controller(8, 64, 16_000);
        c.releaseFailed(c.tryAcquire(1), false);
        assertEquals(8, c.window());
    }

    @Test
    void theLimitNeverDropsBelowOne() {
        DispatchController c = controller(1, 64, 16_000);
        for (int i = 0; i < 20; i++) c.onTick(preempted());
        assertEquals(1, c.window());
        assertTrue(c.budget() >= 4_000, "budget fell below a quarter: " + c.budget());
    }

    @Test
    void greenRestoresTheBudgetUpToItsCeiling() {
        DispatchController c = controller(2, 64, 16_000);
        c.onTick(preempted());
        for (int i = 0; i < 10; i++) c.onTick(green());
        assertEquals(16_000, c.budget());
    }

    @Test
    void thrashingIsReadAsRed() {
        // A falling hit rate at high KV use is the cache evicting what the next turn needs.
        Sensors s = new Sensors(true, 0.7, 0, 0, 0.1, 0.3, Double.NaN, false);
        assertEquals(DispatchController.Light.RED, DispatchController.classify(s));
    }

    @Test
    void aRateLimitIsReadAsRedEvenWithoutMetrics() {
        assertEquals(DispatchController.Light.RED, DispatchController.classify(Sensors.unavailable(true)));
        assertEquals(DispatchController.Light.UNKNOWN, DispatchController.classify(Sensors.unavailable(false)));
    }

    @Test
    void unmeasuredValuesDoNotTurnTheLight() {
        Sensors s = new Sensors(true, 0.1, 0, 0, Double.NaN, Double.NaN, Double.NaN, false);
        assertEquals(DispatchController.Light.GREEN, DispatchController.classify(s));
    }

    @Test
    void theMeasuredPrefillRateIsLearned() {
        DispatchController c = controller(2, 64, 16_000);
        c.onTick(new Sensors(true, 0.1, 0, 0, 0, 0.9, 8_000, false));
        assertEquals(8_000, c.prefillRate(), 1e-9);
        c.onTick(new Sensors(true, 0.1, 0, 0, 0, 0.9, Double.NaN, false));
        assertEquals(8_000, c.prefillRate(), 1e-9, "an interval with no measurement forgot the rate");
    }

    @Test
    void waitersAreAdmittedInArrivalOrder() throws Exception {
        DispatchController c = new DispatchController(64, 1_000_000);
        DispatchController.Permit first = c.acquire(1);
        StringBuilder order = new StringBuilder();
        CountDownLatch queued = new CountDownLatch(2);
        Thread a = Thread.ofVirtual().start(() -> take(c, order, "a", queued));
        while (c.queued() < 1) Thread.sleep(5);
        Thread b = Thread.ofVirtual().start(() -> take(c, order, "b", queued));
        while (c.queued() < 2) Thread.sleep(5);
        c.release(first);
        a.join(5_000);
        b.join(5_000);
        assertEquals("ab", order.toString());
        assertFalse(a.isAlive() || b.isAlive());
    }

    private static void take(DispatchController c, StringBuilder order, String name, CountDownLatch queued) {
        try {
            queued.countDown();
            DispatchController.Permit p = c.acquire(1);
            synchronized (order) {
                order.append(name);
            }
            c.release(p);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
