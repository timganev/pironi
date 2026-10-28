package dev.pironi.tool;

import dev.pironi.agent.SubagentEvents;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** --swarm: a spawn past the limit waits for a slot instead of being refused. */
class SubagentQueueTest {

    @Test
    void aSpawnPastTheLimitIsQueuedAndRunsWhenASlotFrees() throws Exception {
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        var manager = new SubagentManager(2, Duration.ofSeconds(30), SubagentEvents.NOOP, (name, task) -> {
            peak.accumulateAndGet(running.incrementAndGet(), Math::max);
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            running.decrementAndGet();
            return SubagentResult.completed("x", name, "done " + task);
        }, true);

        List<SubagentResult> handles = List.of(
                manager.spawn("a", "1"), manager.spawn("b", "2"), manager.spawn("c", "3"), manager.spawn("d", "4"));

        assertTrue(handles.stream().allMatch(h -> h.status().equals("completed")), handles.toString());
        assertTrue(handles.get(3).output().contains("queued"), handles.get(3).output());
        assertEquals(4, manager.activeCount(), "queued children count as active, so the barrier waits for them");
        release.countDown();
        List<SubagentResult> done = manager.awaitCompleted(Duration.ofSeconds(10));
        assertEquals(4, done.size());
        assertEquals(2, peak.get(), "more children ran at once than the limit");
        manager.close();
    }

    @Test
    void aQueuedChildCancelledBeforeItRunsReportsCancelledNotError() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        var manager = new SubagentManager(1, Duration.ofSeconds(30), SubagentEvents.NOOP, (name, task) -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return SubagentResult.completed("x", name, "done");
        }, true);
        manager.spawn("running", "1");
        manager.spawn("queued", "2");
        Thread.sleep(50);
        manager.cancelAll("discarded");
        release.countDown();
        // Cancelled children no longer count as active, so awaitCompleted does not wait for them;
        // collect until the queued one has reported.
        List<SubagentResult> done = new java.util.ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (done.stream().noneMatch(r -> r.name().equals("queued")) && System.nanoTime() < deadline) {
            done.addAll(manager.drainCompleted());
            Thread.sleep(10);
        }
        SubagentResult queued = done.stream().filter(r -> r.name().equals("queued")).findFirst().orElseThrow();
        assertTrue(queued.isCancelled(), queued.toString());
        manager.close();
    }

    @Test
    void withoutTheFlagTheLimitStillRefuses() {
        CountDownLatch release = new CountDownLatch(1);
        var manager = new SubagentManager(1, Duration.ofSeconds(30), SubagentEvents.NOOP, (name, task) -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return SubagentResult.completed("x", name, "done");
        }, false);
        manager.spawn("a", "1");
        SubagentResult refused = manager.spawn("b", "2");
        assertEquals("error", refused.status());
        release.countDown();
        manager.close();
    }

    @Test
    void theQueueItselfIsBounded() {
        CountDownLatch release = new CountDownLatch(1);
        var manager = new SubagentManager(1, Duration.ofSeconds(30), SubagentEvents.NOOP, (name, task) -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return SubagentResult.completed("x", name, "done");
        }, true);
        for (int i = 0; i < 1 + SubagentManager.MAX_QUEUED; i++) manager.spawn("c" + i, "t");
        SubagentResult overflow = manager.spawn("one-too-many", "t");
        assertEquals("error", overflow.status());
        assertTrue(overflow.output().contains("queue full"), overflow.output());
        release.countDown();
        manager.close();
    }
}
