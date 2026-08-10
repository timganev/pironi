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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the delegation barrier: after the main agent spawns a child, the loop
 * blocks until the child result arrives, so the model has no turn in which it can duplicate
 * the delegated work.
 */
class AgentLoopSubagentTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void barrierWaitsForChildThenDeliversResultInNextPrompt() throws Exception {
        RecordingModel model = new RecordingModel(
                // turn 1: spawn a child
                "{\"thought\":\"spawn\",\"toolCalls\":["
                        + "{\"name\":\"spawn_subagent\",\"arguments\":{\"name\":\"fetch-prices\",\"task\":\"get prices\"}}"
                        + "],\"finalAnswer\":null}",
                // turn 2: finish, using the child's result
                "{\"thought\":\"report\",\"toolCalls\":[],\"finalAnswer\":\"done\"}"
        );
        // Fake gateway that "blocks" (returns immediately here) with a completed child.
        AtomicInteger active = new AtomicInteger(1);
        FakeGateway gateway = new FakeGateway(() -> java.util.List.of(
                SubagentResult.completed("sub_1", "fetch-prices", "AAPL 190, MSFT 410")
        ), active);

        AgentLoop loop = buildLoop(model, gateway, List.of());
        AgentResult result = loop.run("test");

        assertTrue(result.success());
        assertEquals(2, model.calls(), "exactly two model calls: spawn, then final answer");
        // The second prompt (turn 2) must already contain the child's result — the barrier
        // delivered it so the model sees the delegated work is done.
        ChatMessage turn2Prompt = model.requests.get(1).getLast();
        assertTrue(turn2Prompt.content().contains("sub_1"));
        assertTrue(turn2Prompt.content().contains("AAPL 190"));
    }

    @Test
    void barrierPreventsParentDuplicateWork() throws Exception {
        // A counting tool that would be the duplicate: the model SHOULD NOT call it itself
        // once the child has already fetched. A naive model would, so we assert zero calls.
        AtomicInteger parentFetches = new AtomicInteger();
        Tool httpGet = new Tool() {
            @Override public String name() { return "http_get"; }
            @Override public String description() { return "fetch url"; }
            @Override public String argumentSchema() { return "{\"url\":\"...\"}"; }
            @Override public boolean mutating() { return false; }
            @Override public ToolResult execute(com.fasterxml.jackson.databind.JsonNode arguments) {
                parentFetches.incrementAndGet();
                return ToolResult.success("data");
            }
        };
        RecordingModel model = new RecordingModel(
                "{\"thought\":\"spawn\",\"toolCalls\":["
                        + "{\"name\":\"spawn_subagent\",\"arguments\":{\"name\":\"fetch-prices\",\"task\":\"get prices\"}}"
                        + "],\"finalAnswer\":null}",
                "{\"thought\":\"report\",\"toolCalls\":[],\"finalAnswer\":\"done\"}"
        );
        AtomicInteger active = new AtomicInteger(1);
        FakeGateway gateway = new FakeGateway(() -> java.util.List.of(
                SubagentResult.completed("sub_1", "fetch-prices", "prices fetched by child")
        ), active);

        AgentLoop loop = buildLoop(model, gateway, List.of(httpGet));
        assertTrue(loop.run("test").success());
        assertEquals(0, parentFetches.get(),
                "parent must not repeat the fetch a child already did");
    }

    @Test
    void barrierTimeoutTellsParentChildDidNotFinish() throws Exception {
        RecordingModel model = new RecordingModel(
                "{\"thought\":\"spawn\",\"toolCalls\":["
                        + "{\"name\":\"spawn_subagent\",\"arguments\":{\"name\":\"fetch\",\"task\":\"get data\"}}"
                        + "],\"finalAnswer\":null}",
                "{\"thought\":\"report\",\"toolCalls\":[],\"finalAnswer\":\"done\"}"
        );
        // Gateway that never delivers: awaitCompleted returns empty and active stays > 0.
        AtomicInteger active = new AtomicInteger(1);
        FakeGateway gateway = new FakeGateway(java.util.List::of, active);

        AgentLoop loop = buildLoop(model, gateway, List.of(), java.time.Duration.ofSeconds(5));
        assertTrue(loop.run("test").success());
        ChatMessage turn2Prompt = model.requests.get(1).getLast();
        assertTrue(turn2Prompt.content().contains("did not finish"),
                "parent is told the child timed out rather than stalling forever");
    }

    // --- helpers -------------------------------------------------------------

    @Test
    void delegationPromptAppearsOnlyWhenSpawnSubagentRegistered() throws Exception {
        AtomicInteger active = new AtomicInteger(0);
        FakeGateway gateway = new FakeGateway(java.util.List::of, active);
        RecordingModel model = new RecordingModel(
                "{\"thought\":\"f\",\"toolCalls\":[],\"finalAnswer\":\"ok\"}"
        );

        // With spawn_subagent registered -> prompt contains the delegation discipline block.
        AgentLoop withTool = buildLoop(model, gateway, List.of(), java.time.Duration.ofSeconds(5));
        assertTrue(withTool.run("t").success());
        assertTrue(model.requests.getFirst().getFirst().content().contains("spawn_subagent rule"));

        // Without spawn_subagent -> prompt must not contain it (e.g. local/Ollama profile).
        RecordingModel model2 = new RecordingModel(
                "{\"thought\":\"f\",\"toolCalls\":[],\"finalAnswer\":\"ok\"}"
        );
        AgentLoop withoutTool = new AgentLoop(
                model2,
                new DecisionParser(objectMapper),
                objectMapper,
                new ToolRegistry(java.util.List.of()),
                (tool, arguments) -> ApprovalDecision.ALLOW,
                new NoOpTraceWriter(),
                new AgentContext("", "", ""),
                new NoOpStatusReporter(),
                new NoOpVerificationGate(),
                5, 2, null, AgentMemory.none(), null, gateway, java.time.Duration.ofSeconds(5), false
        );
        assertTrue(withoutTool.run("t").success());
        org.junit.jupiter.api.Assertions.assertFalse(
                model2.requests.getFirst().getFirst().content().contains("spawn_subagent rule"),
                "delegation block must be absent when spawn_subagent is not registered"
        );
    }

    private AgentLoop buildLoop(ModelClient model, FakeGateway gateway, List<Tool> tools) {
        return buildLoop(model, gateway, tools, java.time.Duration.ofSeconds(5));
    }

    private AgentLoop buildLoop(
            ModelClient model, FakeGateway gateway, List<Tool> tools, java.time.Duration timeout
    ) {
        List<Tool> allTools = new ArrayList<>(tools);
        allTools.add(new Tool() {
            @Override public String name() { return "spawn_subagent"; }
            @Override public String description() { return "spawn a child"; }
            @Override public String argumentSchema() { return "{\"name\":\"..\",\"task\":\"..\"}"; }
            @Override public boolean mutating() { return false; }
            @Override public ToolResult execute(com.fasterxml.jackson.databind.JsonNode arguments) {
                return ToolResult.success("spawned");
            }
        });
        return new AgentLoop(
                model,
                new DecisionParser(objectMapper),
                objectMapper,
                new ToolRegistry(allTools),
                (tool, arguments) -> ApprovalDecision.ALLOW,
                new NoOpTraceWriter(),
                new AgentContext("", "", ""),
                new NoOpStatusReporter(),
                new NoOpVerificationGate(),
                5,
                2,
                null,
                AgentMemory.none(),
                null,
                gateway,
                timeout,
                false
        );
    }

    /** Deterministic gateway that returns a scripted result when the loop drains. */
    private static final class FakeGateway implements dev.pironi.tool.SubagentGateway {
        private final java.util.function.Supplier<List<SubagentResult>> results;
        private final AtomicInteger active;
        private boolean delivered;

        FakeGateway(java.util.function.Supplier<List<SubagentResult>> results, AtomicInteger active) {
            this.results = results;
            this.active = active;
        }

        @Override
        public List<SubagentResult> awaitCompleted(java.time.Duration timeout) {
            // The non-blocking drain at the start of a turn passes Duration.ZERO; only the
            // real barrier await (non-zero) should hand over the child's result — mirroring
            // how a genuine gateway behaves.
            if (timeout.isZero()) {
                return java.util.List.of();
            }
            if (!delivered) {
                delivered = true;
                List<SubagentResult> r = results.get();
                if (!r.isEmpty()) {
                    active.set(0);
                }
                return r;
            }
            return java.util.List.of();
        }

        @Override public List<String> runningHandles() {
            return active.get() > 0 ? java.util.List.of("sub_1 (fetch)") : java.util.List.of();
        }

        @Override public int activeCount() { return active.get(); }

        @Override public void discardPending() { /* no-op for test */ }
    }

    private static final class RecordingModel implements ModelClient {
        private final ArrayDeque<String> responses;
        final List<List<ChatMessage>> requests = new ArrayList<>();
        private int calls;

        RecordingModel(String... resp) {
            this.responses = new ArrayDeque<>(List.of(resp));
        }

        @Override
        public ModelResponse chat(List<ChatMessage> messages) {
            calls++;
            requests.add(new ArrayList<>(messages));
            String next = responses.isEmpty() ? responses.getLast() : responses.poll();
            return new ModelResponse(next, 0, 0, 0);
        }

        public String model() { return "fake"; }
        int calls() { return calls; }
    }
}
