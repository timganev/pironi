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

    private DispatchController controller(int window, int max, long budget) {
        return new DispatchController(window, max, budget, now::get);
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
    void theWindowBoundsRequestsInFlight() {
        DispatchController c = controller(2, 6, 1_000_000);
        assertNotNull(c.tryAcquire(10));
        assertNotNull(c.tryAcquire(10));
        assertNull(c.tryAcquire(10), "a third request went out with a window of 2");
    }

    @Test
    void theBudgetBoundsUncachedTokensInPrefill() {
        DispatchController c = controller(6, 6, 10_000);
        assertNotNull(c.tryAcquire(8_000));
        assertNull(c.tryAcquire(4_000), "12k uncached tokens went out under a 10k budget");
        assertNotNull(c.tryAcquire(1_000), "a request that fits the budget was held back");
    }

    @Test
    void aLoneRequestIsAdmittedHoweverLarge() {
        DispatchController c = controller(2, 6, 1_000);
        assertNotNull(c.tryAcquire(50_000), "one long prompt would wait forever");
    }

    @Test
    void prefilledTokensStopCountingAgainstTheBudget() {
        DispatchController c = controller(6, 6, 10_000);
        assertNotNull(c.tryAcquire(8_000));
        assertNull(c.tryAcquire(8_000));
        now.addAndGet(TimeUnit.SECONDS.toNanos(2)); // at the default 4k tok/s the first is prefilled
        assertNotNull(c.tryAcquire(8_000), "decode of a prefilled request blocked new prefill");
    }

    @Test
    void greenGrowsTheWindowOnlyUnderDemand() {
        DispatchController c = controller(2, 6, 10_000);
        c.onTick(green());
        assertEquals(2, c.window(), "an idle swarm grew its window");
        c.tryAcquire(1);
        c.tryAcquire(1);
        c.onTick(green());
        assertEquals(3, c.window());
    }

    @Test
    void theWindowNeverPassesItsCeiling() {
        DispatchController c = controller(5, 6, 10_000);
        for (int i = 0; i < 6; i++) c.tryAcquire(1);
        for (int i = 0; i < 5; i++) c.onTick(green());
        assertEquals(6, c.window());
    }

    @Test
    void yellowHoldsTheWindow() {
        DispatchController c = controller(3, 6, 10_000);
        for (int i = 0; i < 3; i++) c.tryAcquire(1);
        assertEquals(DispatchController.Light.YELLOW, c.onTick(yellow()));
        assertEquals(3, c.window());
    }

    @Test
    void redHalvesTheWindowAndCutsTheBudgetOncePerOverload() {
        DispatchController c = controller(6, 6, 16_000);
        assertEquals(DispatchController.Light.RED, c.onTick(preempted()));
        assertEquals(3, c.window());
        assertEquals(12_000, c.budget());
        c.onTick(preempted());
        c.onTick(preempted());
        c.onTick(preempted());
        assertEquals(3, c.window(), "the same overload was punished again during cooldown");
        c.onTick(preempted());
        assertEquals(1, c.window());
    }

    @Test
    void theWindowNeverDropsBelowOne() {
        DispatchController c = controller(1, 6, 16_000);
        for (int i = 0; i < 20; i++) c.onTick(preempted());
        assertEquals(1, c.window());
        assertTrue(c.budget() >= 4_000, "budget fell below a quarter: " + c.budget());
    }

    @Test
    void greenRestoresTheBudgetUpToItsCeiling() {
        DispatchController c = controller(2, 6, 16_000);
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
        DispatchController c = controller(2, 6, 16_000);
        for (int i = 0; i < 20; i++) c.onTick(new Sensors(true, 0.1, 0, 0, 0, 0.9, 8_000, false));
        assertEquals(8_000, c.prefillRate(), 50);
    }

    @Test
    void waitersAreAdmittedInArrivalOrder() throws Exception {
        DispatchController c = new DispatchController(1, 6, 1_000_000);
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
