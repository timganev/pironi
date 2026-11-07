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
        // 4 -> 8 is kept, 8 -> 16 still on trial when the run ends.
        for (int i = 0; i < 40 && c.window() < 16; i++) round(c, 1, 100);
        assertTrue(c.learnedLimit() >= 8, "a run cut short kept " + c.learnedLimit());
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
        // Learned: grows by one, so the drop comes at a limit that was kept, not one on trial.
        DispatchController c = new DispatchController(64, 4, true, 16_000, now::get);
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

    /** Takes {@code n} permits of {@code tokens} uncached tokens each. */
    private java.util.List<DispatchController.Permit> fill(DispatchController c, int n, long tokens) {
        var permits = new java.util.ArrayList<DispatchController.Permit>();
        for (int i = 0; i < n; i++) permits.add(assertNotNullAndGet(c.tryAcquire(tokens)));
        return permits;
    }

    private static DispatchController.Permit assertNotNullAndGet(DispatchController.Permit p) {
        assertNotNull(p, "the limit was full");
        return p;
    }

    private static Sensors queue(double seconds) {
        return new Sensors(true, 0.3, 1, 0, seconds, 0.9, Double.NaN, false);
    }

    @Test
    void aLongQueueAloneStepsBackByOne() {
        // One request past what vLLM runs at once waits seconds: red, but nothing is evicted.
        DispatchController c = new DispatchController(64, 9, true, 16_000, now::get);
        fill(c, 9, 100);
        assertEquals(DispatchController.Light.RED, c.onTick(queue(11.8)));
        assertEquals(8, c.window());
        assertEquals(16_000, c.budget());
        for (int i = 0; i < DispatchController.QUEUE_COOLDOWN_TICKS; i++) c.onTick(queue(11.8 + i));
        assertEquals(8, c.window(), "the same queue was punished again while it drained");
    }

    @Test
    void aQueueReadWhileTheRunDrainsLeavesTheLimitAndWhatWasLearned() {
        // The end of a run: no one waits any more, the last requests finish one by one and the
        // queue times of the ones that waited inside the server come in late.
        DispatchController c = new DispatchController(64, 8, true, 16_000, now::get);
        c.onTick(yellow());  // holds the limit at 8 while it is measured
        for (int i = 0; i < 6; i++) round(c, 1, 100);
        int learned = c.learnedLimit();
        assertEquals(8, learned);
        var permits = fill(c, 8, 100);
        double[] late = {9.1, 9.6, 10.2, 11.0, 12.5, 13.0, 14.0};
        for (int i = 0; i < late.length; i++) {
            now.addAndGet(TimeUnit.SECONDS.toNanos(1));
            c.release(permits.get(i), 100);
            assertTrue(c.onTick(queue(late[i])) != DispatchController.Light.RED, "red at " + late[i] + " s");
        }
        assertEquals(8, c.window(), "a draining run stepped the limit back");
        assertEquals(learned, c.learnedLimit(), "a draining run lowered what is kept for next time");
    }

    @Test
    void aQueueThatShrinksIsNotRed() {
        DispatchController c = new DispatchController(64, 6, true, 16_000, now::get);
        fill(c, 6, 100);
        assertEquals(DispatchController.Light.RED, c.onTick(queue(12)));
        assertEquals(5, c.window());
        for (int i = 0; i < DispatchController.QUEUE_COOLDOWN_TICKS; i++) c.onTick(queue(12));
        assertEquals(DispatchController.Light.YELLOW, c.onTick(queue(8)), "a queue that drains read as red");
        assertEquals(5, c.window());
    }

    @Test
    void aQueueThatPrefillExplainsIsNotRed() {
        // A slow server prefills four long uncached prompts one after another: the last waits for
        // the other three. On qwen38 13.7k tokens each at ~1.4k tok/s gave 8.6 s with KV at 0.45.
        DispatchController c = new DispatchController(64, 4, true, 1_000_000, now::get);
        c.onTick(new Sensors(true, 0.45, 0, 0, 0.0, 0.0, 1_400, false));
        fill(c, 4, 13_700);
        Sensors s = new Sensors(true, 0.45, 0, 0, 8.6, 0.0, 1_400, false);
        assertTrue(c.onTick(s) != DispatchController.Light.RED, "prefill read as an overload");
        assertEquals(4, c.window());
        // The same queue with short prompts is the limit's doing.
        DispatchController short_ = new DispatchController(64, 4, true, 1_000_000, now::get);
        short_.onTick(new Sensors(true, 0.45, 0, 0, 0.0, 0.0, 1_400, false));
        fill(short_, 4, 200);
        assertEquals(DispatchController.Light.RED, short_.onTick(s));
        assertEquals(3, short_.window());
    }

    @Test
    void aColdStartDoublesOnGreen() {
        DispatchController c = controller(4, 64, 16_000);
        c.onTick(green());
        round(c, 1, 100);  // the first wave: growth on green needs no measurement
        assertEquals(8, c.window());
        for (int i = 0; i < 4 && c.window() == 8; i++) round(c, 1, 100);
        assertEquals(16, c.window());
        assertTrue(c.slowStart());
    }

    @Test
    void aDoublingStopsWhereTheKvCacheWouldFill() {
        DispatchController c = controller(4, 64, 16_000);
        var permits = fill(c, 4, 100);
        // Four in flight use 0.48: about six fit below the yellow-red line, not eight.
        c.onTick(new Sensors(true, 0.48, 0, 0, Double.NaN, 0.9, Double.NaN, false));
        assertNull(c.tryAcquire(100));
        now.addAndGet(TimeUnit.SECONDS.toNanos(1));
        for (var p : permits) c.release(p, 100);
        assertEquals(6, c.window());
    }

    @Test
    void aQueueAfterADoublingGoesBackToTheLimitBeforeItAndEndsTheDoubling() {
        DispatchController c = controller(4, 64, 16_000);
        c.onTick(green());
        round(c, 1, 100);
        for (int i = 0; i < 4 && c.window() == 8; i++) round(c, 1, 100);
        assertEquals(16, c.window());
        fill(c, 16, 100);
        assertEquals(DispatchController.Light.RED, c.onTick(queue(20)));
        assertEquals(8, c.window(), "16 queued in the server; 8 did not");
        assertFalse(c.slowStart());
    }

    @Test
    void anOverloadEndsTheDoubling() {
        DispatchController c = controller(4, 64, 16_000);
        c.onTick(green());
        round(c, 1, 100);
        assertEquals(8, c.window());
        c.onTick(preempted());
        assertEquals(4, c.window());
        assertFalse(c.slowStart());
        for (int i = 0; i < 6; i++) c.onTick(green());
        for (int i = 0; i < 30 && c.window() == 4; i++) round(c, 1, 100);
        assertEquals(5, c.window(), "after an overload the limit grows by one");
    }

    @Test
    void aLearnedStartNeitherDoublesNorTriesFewerFirst() {
        // Blind, a server with 6 slots: one more never pays, fewer cost.
        DispatchController warm = new DispatchController(64, 6, true, 16_000, now::get);
        assertFalse(warm.slowStart());
        int first = 0;
        for (int i = 0; i < 12 && first == 0; i++) {
            round(warm, Math.max(1, warm.window() / 6.0), 100);
            if (warm.window() != 6) first = warm.window();
        }
        assertEquals(7, first, "a learned start first went to " + first);
        // A start at a ceiling the user gave still tries fewer first.
        DispatchController told = controller(6, 6, 16_000);
        for (int i = 0; i < 12 && told.window() == 6; i++) round(told, 1, 100);
        assertTrue(told.window() < 6, "a told ceiling was not tried lower: " + told.window());
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
        DispatchController c = controller(4, 64, 16_000);
        for (int i = 0; i < 5; i++) c.onTick(new Sensors(true, 0.3, 0, 0, 0.1, 0.9, Double.NaN, false));
        int before = c.window();
        Sensors s = new Sensors(true, 0.7, 0, 0, 0.1, 0.3, Double.NaN, false);
        assertEquals(DispatchController.Light.RED, c.onTick(s));
        assertEquals(Math.max(1, before / 2), c.window());
    }

    @Test
    void aHitRateThatWasNeverHighIsNotThrashing() {
        // Agents with prompts of their own share no prefix: the hit rate is low from the start,
        // and a KV cache filling with their work is no reason to halve the limit (qwen38, 07.11).
        DispatchController c = controller(5, 64, 16_000);
        DispatchController.Light last = null;
        for (int i = 0; i < 10; i++) last = c.onTick(new Sensors(true, 0.7, 0, 0, 0.1, 0.02, Double.NaN, false));
        assertEquals(DispatchController.Light.YELLOW, last);
        assertEquals(5, c.window());
    }

    @Test
    void oneHighReadingDoesNotMakeAHighUsualHitRate() {
        DispatchController c = controller(5, 64, 16_000);
        c.onTick(new Sensors(true, 0.3, 0, 0, 0.1, 0.0, Double.NaN, false));
        c.onTick(new Sensors(true, 0.3, 0, 0, 0.1, 0.8, Double.NaN, false));
        assertEquals(DispatchController.Light.YELLOW,
                c.onTick(new Sensors(true, 0.7, 0, 0, 0.1, 0.0, Double.NaN, false)));
        assertEquals(5, c.window());
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
