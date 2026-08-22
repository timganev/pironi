package dev.pironi.tool;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubagentManagerTest {

    @Test
    void spawnReturnsHandleImmediatelyAndChildCompletes() throws Exception {
        // The child has to still be running for "counted while it runs" to mean anything. With a
        // task runner that returns at once it could be done before the assertion, and on a loaded
        // machine it was - the two commits that waited for the count everywhere else missed here.
        var childStarted = new java.util.concurrent.CountDownLatch(1);
        var releaseChild = new java.util.concurrent.CountDownLatch(1);
        var manager = new SubagentManager(2, (ignoredName, subtask) -> {
            childStarted.countDown();
            try {
                releaseChild.await(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return SubagentResult.completed("x", "weather", "data for " + subtask);
        });

        SubagentResult handle = manager.spawn("weather", "Sofia 5-day");

        assertEquals("completed", handle.status());
        assertTrue(handle.output().contains("started"), "handle should be returned immediately");
        assertTrue(childStarted.await(5, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(1, manager.activeCount());
        releaseChild.countDown();

        // Poll until the virtual thread finishes (bounded wait).
        SubagentManager.Completion completion = waitForCompletion(manager);
        assertNotNull(completion);
        assertEquals("sub_", completion.result().id().substring(0, 4),
                "id is normalized to the real handle, not the taskRunner's hardcoded id");
        assertTrue(completion.result().output().contains("Sofia 5-day"));
        // finish() queues the result before it clears the active count - deliberately, so a
        // result is never lost to a caller arriving in between - so seeing the completion says
        // nothing yet about the counter. Asserting it directly passed on a fast machine and
        // failed on a loaded runner.
        assertEquals(0, waitForIdle(manager), "the child stops counting as active");
        manager.close();
    }

    /**
     * Bounded wait for the active count to settle; returns whatever it reached.
     *
     * <p>Every "is it idle yet" assertion needs this. finish() queues a child's result before it
     * clears the count, deliberately, so that a caller arriving in between drains the result
     * instead of losing it - which means seeing a completion says nothing about the counter yet.
     */
    private static int waitForIdle(SubagentManager manager) {
        for (int attempt = 0; attempt < 200 && manager.activeCount() > 0; attempt++) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return manager.activeCount();
    }

    @Test
    void capRejectsSpawnBeyondLimit() {
        var manager = new SubagentManager(1, (ignoredName, subtask) -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return SubagentResult.completed("x", "n", "done");
        });

        SubagentResult first = manager.spawn("a", "one");
        assertEquals("completed", first.status());
        assertEquals(1, manager.activeCount());

        // Second spawn while first still active must hit the cap.
        SubagentResult rejected = manager.spawn("b", "two");
        assertEquals("error", rejected.status());
        assertTrue(rejected.output().contains("limit reached"));

        waitForCompletion(manager);
        // Now the cap is free again.
        SubagentResult accepted = manager.spawn("c", "three");
        assertEquals("completed", accepted.status());
        manager.close();
    }

    @Test
    void drainCollectsAllFinishedChildren() {
        var manager = new SubagentManager(4, (ignoredName, subtask) -> {
            try {
                Thread.sleep(10);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return SubagentResult.completed("id-" + subtask, subtask, "out[" + subtask + "]");
        });

        manager.spawn("one", "one");
        manager.spawn("two", "two");
        manager.spawn("three", "three");

        List<SubagentManager.Completion> all = waitForList(manager, 3);
        assertEquals(3, all.size());
        assertEquals(java.util.Set.of("one", "two", "three"),
                all.stream().map(SubagentManager.Completion::name).collect(java.util.stream.Collectors.toSet()));
        assertEquals(0, waitForIdle(manager));
        manager.close();
    }

    @Test
    void emptyDrainReturnsEmptyList() {
        var manager = new SubagentManager(2, (ignoredName, subtask) -> SubagentResult.completed("x", "n", "d"));
        assertTrue(manager.drain().isEmpty());
        assertNull(manager.poll());
        manager.close();
    }

    @Test
    void awaitCompletedZeroMatchesNonBlockingDrain() throws Exception {
        var manager = new SubagentManager(2, (ignoredName, subtask) ->
                SubagentResult.completed("x", "n", "out[" + subtask + "]"));
        // Nothing spawned yet -> non-blocking drain must return immediately and empty.
        assertTrue(manager.drainCompleted().isEmpty());
        manager.close();
    }

    @Test
    void awaitCompletedWaitsForActiveChildAndReturnsNormalizedId() throws Exception {
        var manager = new SubagentManager(2, (ignoredName, subtask) -> {
            try {
                Thread.sleep(20);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return SubagentResult.completed("child", "weather", "done[" + subtask + "]");
        });
        manager.spawn("weather", "Sofia");
        List<SubagentResult> ready = manager.awaitCompleted(java.time.Duration.ofSeconds(5));
        assertEquals(1, ready.size());
        assertEquals("sub_", ready.get(0).id().substring(0, 4));
        assertEquals(0, waitForIdle(manager));
        assertTrue(manager.runningHandles().isEmpty());
        manager.close();
    }

    @Test
    void aFinishedChildResultIsNeverLostToTheAwaitRace() throws Exception {
        // Regression: finish() used to mark the child inactive before queueing its result.
        // awaitCompleted decides whether to wait from the active set, so landing in that
        // window returned empty and the completed child's output vanished. Rare on a fast
        // machine, reproducible on a loaded CI runner, so repeat with an immediate child.
        //
        // The same window also decided how long the wait took. Hitting it made awaitCompleted
        // sit out its whole timeout for a child that had already finished, which is why this
        // test used to cost five seconds per hit and was the slowest in the suite. The elapsed
        // assertion below keeps that from coming back quietly: the result was always returned,
        // so only the clock ever showed it.
        for (int attempt = 0; attempt < 300; attempt++) {
            var manager = new SubagentManager(2,
                    (name, subtask) -> SubagentResult.completed("x", name, "done"));
            SubagentResult handle = manager.spawn("weather", "Sofia");
            long startedAt = System.nanoTime();
            List<SubagentResult> ready = manager.awaitCompleted(java.time.Duration.ofSeconds(5));
            long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;
            int index = attempt;
            assertTrue(elapsedMillis < 2_000,
                    () -> "attempt " + index + ": awaiting a finished child must not wait out"
                            + " the timeout, took " + elapsedMillis + " ms");
            // Supplier form: the message calls drainCompleted(), which empties the queue, so it
            // must only run when the assertion actually fails.
            assertEquals(1, ready.size(), () ->
                    "attempt " + index + ": a completed child must always be reported"
                            + " | spawn=" + handle.status()
                            + " | active=" + manager.activeCount()
                            + " | drainNow=" + manager.drainCompleted().size());
            manager.close();
        }
    }

    @Test
    void discardPendingDropsStaleResults() throws Exception {
        var manager = new SubagentManager(2, (ignoredName, subtask) -> SubagentResult.completed("x", "n", "d"));
        manager.spawn("one", "one");
        List<SubagentResult> ready = manager.awaitCompleted(java.time.Duration.ofSeconds(5));
        assertEquals(1, ready.size());
        // Discard must drop any results still queued (none here since await drained it).
        manager.discardPending();
        assertTrue(manager.drainCompleted().isEmpty());
        manager.close();
    }

    @Test
    void interruptDeadlineResolvesStuckChildAndClearsActive() throws Exception {
        // A child that never returns on its own; the manager must interrupt it at the deadline.
        var manager = new SubagentManager(1, java.time.Duration.ofMillis(400), (ignoredName, subtask) -> {
            try {
                Thread.sleep(10_000);
            } catch (Throwable ignored) {
                // interrupted by manager
            }
            return SubagentResult.completed("x", "stuck", "never");
        });
        manager.spawn("stuck", "hang");
        assertEquals(1, manager.activeCount());
        // The stuck child never completes, so this await always burns its whole timeout: it is
        // the deadline under test (400 ms) that has to fire, not this one. Five seconds here
        // bought nothing and made the slowest test in the suite.
        manager.awaitCompleted(java.time.Duration.ofSeconds(1));
        assertEquals(0, waitForIdle(manager), "deadline interrupt resolves the stuck child");
        manager.close();
    }

    @Test
    void runningHandlesReflectsActiveChildren() throws Exception {
        var manager = new SubagentManager(2, (ignoredName, subtask) -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return SubagentResult.completed("x", "weather", "done");
        });
        manager.spawn("weather", "Sofia");
        // Immediately after spawn, the child is running and its handle is observable.
        List<String> handles = manager.runningHandles();
        assertEquals(1, handles.size());
        assertTrue(handles.getFirst().startsWith("sub_"), "handle is the sub_ id");
        assertTrue(handles.getFirst().contains("weather"), "handle includes the child name");
        // Let it finish, then no handles remain.
        manager.awaitCompleted(java.time.Duration.ofSeconds(5));
        assertTrue(manager.runningHandles().isEmpty());
        assertEquals(0, waitForIdle(manager));
        manager.close();
    }

    @Test
    void discardPendingDoesNotInterruptRunningChild() throws Exception {
        // Regression for the 0ms InterruptedException bug: a routine pending-drain at the start
        // of a new parent run must NOT kill an in-flight child.
        java.util.concurrent.atomic.AtomicBoolean childInterrupted = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        var manager = new SubagentManager(2, (ignoredName, subtask) -> {
            entered.countDown();
            try {
                release.await(2, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                childInterrupted.set(true);
                Thread.currentThread().interrupt();
            }
            return SubagentResult.completed("x", "weather", "done");
        });
        manager.spawn("weather", "Sofia");
        assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS), "child entered");
        // This is what the loop calls at the start of every new turn/run.
        manager.discardPending();
        // The child must still be alive (not interrupted).
        assertFalse(childInterrupted.get(), "discardPending must not interrupt the child");
        release.countDown();
        List<SubagentResult> ready = manager.awaitCompleted(java.time.Duration.ofSeconds(2));
        assertEquals(1, ready.size());
        assertEquals("completed", ready.get(0).status());
        manager.close();
    }

    @Test
    void cancelChildOnNewHandleIsAbsorbedWithoutStickyInterrupt() throws Exception {
        // Simulate the NEW-state cancellation that used to kill the child via a sticky flag.
        java.util.concurrent.atomic.AtomicBoolean sawInterrupted = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.CountDownLatch flagRead = new java.util.concurrent.CountDownLatch(1);
        var manager = new SubagentManager(2, (ignoredName, subtask) -> {
            // If the pre-start interrupt leaked, the child would see isInterrupted()==true here.
            if (Thread.currentThread().isInterrupted()) sawInterrupted.set(true);
            flagRead.countDown();
            return SubagentResult.completed("x", "weather", "ok");
        });
        // Read the flag at the child's first instruction, before any cancel can reach it.
        // Sleeping instead raced: when the child won, discardPendingResults() interrupted it
        // legitimately as a RUNNING handle and the assertion blamed a sticky pre-start flag
        // for what is correct cancellation behaviour.
        manager.spawn("weather", "Sofia");
        assertTrue(flagRead.await(5, java.util.concurrent.TimeUnit.SECONDS), "child body must run");
        manager.discardPendingResults();
        assertFalse(sawInterrupted.get(), "child must not start with a sticky pre-start interrupt");
        assertEquals(0, waitForIdle(manager), "cancelled child no longer counts as active");
        manager.close();
    }

    @Test
    void zeroDurationDrainDoesNotCancelActiveChild() throws Exception {
        // Regression: a just-spawned child must NOT be killed at 0ms by the turn-start non-blocking
        // drain (which used to trip a TIMEOUT cancel through awaitCompleted(Duration.ZERO)).
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        var manager = new SubagentManager(2, (ignoredName, subtask) -> {
            started.countDown();
            try {
                release.await(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return SubagentResult.error("x", "slow-child", "interrupted");
            }
            return SubagentResult.completed("x", "slow-child", "done");
        });
        manager.spawn("slow-child", "do work");
        assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS), "child started");

        // exactly what AgentLoop.drainSubagentResults does at the start of every async turn
        assertTrue(manager.drainCompleted().isEmpty(), "active child yields no drained result");
        assertEquals(1, manager.activeCount(), "child still active (not cancelled)");

        release.countDown();
        List<SubagentResult> ready = manager.awaitCompleted(java.time.Duration.ofSeconds(5));
        assertEquals(1, ready.size());
        assertEquals("completed", ready.get(0).status());
        manager.close();
    }

    @Test
    void zeroDurationAwaitIsRejectedAsProgrammingError() {
        var manager = new SubagentManager(2, (ignoredName, subtask) ->
                SubagentResult.completed("x", "n", "d"));
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> manager.awaitCompleted(java.time.Duration.ZERO));
        manager.close();
    }

    private static SubagentManager.Completion waitForCompletion(SubagentManager manager) {
        for (int i = 0; i < 200; i++) {
            SubagentManager.Completion next = manager.poll();
            if (next != null) return next;
            try {
                Thread.sleep(5);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return null;
    }

    private static List<SubagentManager.Completion> waitForList(SubagentManager manager, int count) {
        java.util.List<SubagentManager.Completion> out = new java.util.ArrayList<>();
        for (int i = 0; i < 200; i++) {
            out.addAll(manager.drain());
            if (out.size() >= count) return out;
            try {
                Thread.sleep(5);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return out;
    }
}
