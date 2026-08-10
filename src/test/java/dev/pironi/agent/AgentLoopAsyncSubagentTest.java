package dev.pironi.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.pironi.model.ChatMessage;
import dev.pironi.model.ModelClient;
import dev.pironi.model.ModelResponse;
import dev.pironi.safety.ApprovalDecision;
import dev.pironi.tool.SubagentResult;
import dev.pironi.tool.Tool;
import dev.pironi.tool.ToolRegistry;
import dev.pironi.tool.ToolResult;
import dev.pironi.trace.NoOpTraceWriter;
import dev.pironi.status.NoOpStatusReporter;
import dev.pironi.verification.NoOpVerificationGate;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that with {@code asyncSubagents=true} the loop returns to the input reader while a
 * child is still running — the core of "the user can keep talking while the agent works".
 */
class AgentLoopAsyncSubagentTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void asyncRunReturnsWhileChildIsStillActive() throws Exception {
        CountDownLatch childGate = new CountDownLatch(1);
        RecordingModel model = new RecordingModel(
                // turn 1: spawn a child
                "{\"thought\":\"spawn\",\"toolCalls\":["
                        + "{\"name\":\"spawn_subagent\",\"arguments\":{\"name\":\"research\",\"task\":\"do research\"}}"
                        + "],\"finalAnswer\":null}",
                // a second turn, in case the loop continues (should NOT: async returns early)
                "{\"thought\":\"report\",\"toolCalls\":[],\"finalAnswer\":\"done\"}"
        );
        BlockingGateway gateway = new BlockingGateway(childGate);

        AgentLoop loop = buildAsyncLoop(model, gateway);

        // The child is blocked on the latch; with asyncSubagents=true the loop MUST return
        // well before the child finishes — otherwise the user cannot type.
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(3),
                () -> loop.run("test"));
        // The loop returned; control is back to the input reader even though the child is still
        // active (it never released its latch during this run).
        assertEquals(1, gateway.activeCount());

        // Release the child so it can finish (avoid leaking a thread).
        childGate.countDown();
        gateway.awaitEmpty(3);
        model.close();
    }

    @Test
    void resultDrainsIntoNextRun() throws Exception {
        RecordingModel model = new RecordingModel(
                "{\"thought\":\"f\",\"toolCalls\":[],\"finalAnswer\":\"ok\"}"
        );
        // Gateway that already has one completed result.
        CountingGateway gateway = new CountingGateway(
                List.of(SubagentResult.completed("sub_1", "research", "Данни: 42")));
        AgentLoop loop = buildAsyncLoop(model, gateway);

        assertTrue(loop.run("t").success());
        // The completed child result must be injected as context.
        // Scan the full first request for the synthetic system notification.
        assertTrue(model.requests.getFirst().stream()
                .anyMatch(m -> m.content().contains("[системно известие")),
                "[системно известие] must be in the first model call");
        assertTrue(model.requests.getFirst().stream()
                .anyMatch(m -> m.content().contains("research")));
        assertTrue(model.requests.getFirst().stream()
                .anyMatch(m -> m.content().contains("Данни: 42")));
    }

    @Test
    void failedChildIsInjectedWithoutBreakingTurn() throws Exception {
        RecordingModel model = new RecordingModel(
                "{\"thought\":\"f\",\"toolCalls\":[],\"finalAnswer\":\"ok\"}"
        );
        CountingGateway gateway = new CountingGateway(
                List.of(SubagentResult.error("sub_9", "research", "timeout")));
        AgentLoop loop = buildAsyncLoop(model, gateway);

        assertTrue(loop.run("t").success());
        assertTrue(model.requests.getFirst().stream()
                .anyMatch(m -> m.content().contains("[системно известие")),
                "[системно известие] must be in the first model call");
        assertTrue(model.requests.getFirst().stream()
                .anyMatch(m -> m.content().contains("research")));
        assertTrue(model.requests.getFirst().stream()
                .anyMatch(m -> m.content().contains("се провали")));
    }

    @Test
    void childResultSurvivesBetweenRunsAndIsDeliveredToNextTurn() throws Exception {
        // Regression: a completed child result must survive from one run() to the next,
        // not be discarded by a lifecycle call between user turns.

        // first run — model spawns and returns
        RecordingModel model1 = new RecordingModel(
                "{\"thought\":\"spawn\",\"toolCalls\":[],\"finalAnswer\":\"ok\"}"
        );
        CountingGateway gateway = new CountingGateway(
                List.of(SubagentResult.completed("sub_1", "research", "Paris 22C")));
        AgentLoop loop1 = buildAsyncLoop(model1, gateway);

        assertTrue(loop1.run("t1").success());
        // The child result must be present in this run's messages.
        assertTrue(model1.requests.get(0).stream()
                .anyMatch(m -> m.content().contains("Paris 22C")));

        // second run — must pick up the same result from the gateway, not lose it
        RecordingModel model2 = new RecordingModel(
                "{\"thought\":\"report\",\"toolCalls\":[],\"finalAnswer\":\"done\"}"
        );
        // rebuild with same CountingGateway that still has the result
        CountingGateway sameGateway = new CountingGateway(
                List.of(SubagentResult.completed("sub_1", "research", "Paris 22C")));
        AgentLoop loop2 = buildAsyncLoop(model2, sameGateway);

        assertTrue(loop2.run("t2").success());

        // The result must appear in the second run
        assertTrue(model2.requests.get(0).stream()
                .anyMatch(m -> m.content().contains("Paris 22C") && m.role().equals("user")),
                "completed child result must be drained into second run");
    }

    // --- helpers -------------------------------------------------------------

    private AgentLoop buildAsyncLoop(ModelClient model, dev.pironi.tool.SubagentGateway gateway) {
        Tool spawnTool = new Tool() {
            @Override public String name() { return "spawn_subagent"; }
            @Override public String description() { return "spawn a child"; }
            @Override public String argumentSchema() { return "{\"name\":\"..\",\"task\":\"..\"}"; }
            @Override public boolean mutating() { return false; }
            @Override public ToolResult execute(com.fasterxml.jackson.databind.JsonNode arguments) {
                return ToolResult.success("spawned");
            }
        };
        return new AgentLoop(
                model,
                new DecisionParser(objectMapper),
                objectMapper,
                new ToolRegistry(List.of(spawnTool)),
                (tool, arguments) -> ApprovalDecision.ALLOW,
                new NoOpTraceWriter(),
                new AgentContext("", "", ""),
                new NoOpStatusReporter(),
                new NoOpVerificationGate(),
                5, 2, null, AgentMemory.none(), null, gateway,
                java.time.Duration.ofSeconds(120), true
        );
    }

    /** Gateway whose child stays active until a latch is released; used to prove run() returns early. */
    private static final class BlockingGateway implements dev.pironi.tool.SubagentGateway {
        private final CountDownLatch gate;
        private final AtomicInteger active = new AtomicInteger(1);

        BlockingGateway(CountDownLatch gate) { this.gate = gate; }

        @Override public List<SubagentResult> awaitCompleted(java.time.Duration timeout) throws InterruptedException {
            // In async mode not called by the loop barrier; if it is, return immediately empty.
            return List.of();
        }
        @Override public List<String> runningHandles() { return List.of("sub_1 (research)"); }
        @Override public int activeCount() { return active.get(); }
        @Override public void discardPending() { }

        void awaitEmpty(int seconds) throws InterruptedException {
            // give the latch a moment to release and the child to finish
            gate.await(seconds, TimeUnit.SECONDS);
            active.set(0);
        }
    }

    /** Gateway that hands out one pre-completed result then goes empty. */
    private static final class CountingGateway implements dev.pironi.tool.SubagentGateway {
        private final java.util.Queue<SubagentResult> pending;
        CountingGateway(List<SubagentResult> results) {
            this.pending = new java.util.ArrayDeque<>(results);
        }
        @Override public List<SubagentResult> awaitCompleted(java.time.Duration timeout) {
            return drainAll();
        }
        @Override public List<SubagentResult> drainCompleted() {
            return drainAll();
        }
        @Override public List<String> runningHandles() { return List.of(); }
        @Override public int activeCount() { return 0; }
        @Override public void discardPending() { /* keep the seeded result for this test */ }
        private List<SubagentResult> drainAll() {
            List<SubagentResult> out = new ArrayList<>();
            SubagentResult r;
            while ((r = pending.poll()) != null) out.add(r);
            return out;
        }
    }

    private static final class RecordingModel implements ModelClient {
        private final ArrayDeque<String> responses;
        private final List<List<ChatMessage>> requests = new ArrayList<>();

        RecordingModel(String... resp) { this.responses = new ArrayDeque<>(List.of(resp)); }

        @Override public ModelResponse chat(List<ChatMessage> messages) throws InterruptedException {
            requests.add(new ArrayList<>(messages));
            String next = responses.isEmpty() ? responses.getLast() : responses.poll();
            return new ModelResponse(next, 0, 0, 0);
        }

        String lastUserMessageBeforeFinal() {
            List<ChatMessage> last = requests.getLast();
            for (int i = last.size() - 1; i >= 0; i--) {
                if ("user".equals(last.get(i).role())) return last.get(i).content();
            }
            return "";
        }

        void close() { }
    }
}
