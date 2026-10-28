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
    void withNothingKnownTheLimitStartsAtFour() {
        DispatchController c = new DispatchController(64, 16_000);
        assertEquals(ConcurrencyLimiter.BLIND_START, c.window());
        assertEquals(1, new DispatchController(1, 16_000).window(), "a one-slot server is not overrun");
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

    /**
     * One round: fills the limit, asks for one more (held back), lets {@code seconds} pass and
     * finishes them all with {@code tokens} each.
     */
    private void round(DispatchController c, double seconds, long tokens) {
        int limit = c.window();
        var permits = new java.util.ArrayList<DispatchController.Permit>();
        for (int i = 0; i < limit; i++) permits.add(c.tryAcquire(100));
        assertNull(c.tryAcquire(100));
        now.addAndGet((long) (seconds * 1e9));
        for (var p : permits) c.release(p, tokens);
    }

    @Test
    void withoutMetricsTheLimitGrowsWhileOneMorePays() {
        DispatchController c = controller(4, 64, 16_000);
        // Every request as fast as alone: throughput grows with the limit.
        for (int i = 0; i < 40 && c.window() < 8; i++) round(c, 1, 100);
        assertTrue(c.window() >= 8, "the limit stayed at " + c.window() + " with no /metrics");
    }

    @Test
    void aLimitStillClimbingIsKeptForTheNextRun() {
        DispatchController c = controller(4, 64, 16_000);
        for (int i = 0; i < 40 && c.window() < 8; i++) round(c, 1, 100);
        assertTrue(c.learnedLimit() >= 7, "a run cut short kept " + c.learnedLimit());
    }

    @Test
    void withoutMetricsTheLimitStopsWhereOneMoreBringsNothing() {
        DispatchController c = controller(4, 64, 16_000);
        // A server with 4 slots: whatever is in flight, 400 tokens a second come back.
        for (int i = 0; i < 60; i++) {
            int limit = c.window();
            round(c, limit / 4.0, 100);
        }
        assertTrue(c.window() <= 5, "the limit walked up to " + c.window() + " with no gain");
    }

    @Test
    void aClearDropInThroughputStepsBack() {
        DispatchController c = controller(4, 64, 16_000);
        for (int i = 0; i < 40 && c.window() < 6; i++) round(c, 1, 100);
        int top = c.window();
        // From here every round is twice as slow: the server thrashes at this limit.
        for (int i = 0; i < 12 && c.window() == top; i++) round(c, 2, 100);
        assertEquals(top - 1, c.window());
    }

    @Test
    void greenLetsTheLimitGrowWithoutWaitingForAGain() {
        DispatchController c = controller(4, 64, 16_000);
        c.onTick(green());
        for (int i = 0; i < 30 && c.window() < 8; i++) round(c, c.window() / 4.0, 100);  // no gain at all
        assertTrue(c.window() >= 8, "green held the limit at " + c.window());
    }

    @Test
    void aLimitSetByTheDemandIsNotKeptAsLearned() {
        DispatchController c = controller(8, 64, 16_000);
        for (int i = 0; i < 20; i++) {
            var p = c.tryAcquire(100);  // one agent, one request at a time
            now.addAndGet(TimeUnit.SECONDS.toNanos(1));
            c.release(p, 100);
        }
        assertEquals(1, c.window(), "a limit of 8 that one agent never fills");
        assertEquals(0, c.learnedLimit(), "the next run would start at 1 for a swarm");
    }

    @Test
    void yellowStopsGrowth() {
        DispatchController c = controller(3, 64, 10_000);
        assertEquals(DispatchController.Light.YELLOW, c.onTick(yellow()));
        for (int i = 0; i < 20; i++) round(c, 1, 100);
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
    void aLongQueueAloneStepsBackByOne() {
        // One request past what vLLM runs at once waits seconds: red, but nothing is evicted.
        DispatchController c = controller(9, 64, 16_000);
        Sensors queued = new Sensors(true, 0.3, 1, 0, 11.8, 0.9, Double.NaN, false);
        assertEquals(DispatchController.Light.RED, c.onTick(queued));
        assertEquals(8, c.window());
        assertEquals(16_000, c.budget());
        for (int i = 0; i < DispatchController.QUEUE_COOLDOWN_TICKS; i++) c.onTick(queued);
        assertEquals(8, c.window(), "the same queue was punished again while it drained");
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
        DispatchController c = controller(1, 64, 1_000_000);
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
