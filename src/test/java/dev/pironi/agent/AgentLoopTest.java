package dev.pironi.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.pironi.model.ChatMessage;
import dev.pironi.model.ModelClient;
import dev.pironi.model.ModelResponse;
import dev.pironi.safety.ApprovalDecision;
import dev.pironi.safety.Workspace;
import dev.pironi.tool.RunCommandTool;
import dev.pironi.tool.Tool;
import dev.pironi.tool.ToolRegistry;
import dev.pironi.tool.ToolResult;
import dev.pironi.trace.NoOpTraceWriter;
import dev.pironi.status.NoOpStatusReporter;
import dev.pironi.verification.NoOpVerificationGate;
import dev.pironi.verification.VerificationGate;
import dev.pironi.verification.VerificationResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentLoopTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void repairsProtocolThenFinishes() throws Exception {
        RecordingModelClient model = new RecordingModelClient(
                "not json",
                """
                {"thought":"fixed","toolCalls":[],"finalAnswer":"done"}
                """
        );

        AgentResult result = loop(model, List.of()).run("test");

        assertTrue(result.success());
        assertEquals(2, result.turns());
        assertTrue(model.requests.get(1).getLast().content().contains("violated"));
    }

    @Test
    void repairsProviderReportedTruncationWithTargetedGuidance() throws Exception {
        List<List<ChatMessage>> requests = new ArrayList<>();
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        ModelClient model = messages -> {
            requests.add(List.copyOf(messages));
            if (calls.getAndIncrement() == 0) {
                return new ModelResponse("{\"thought\":\"cut", 10, 20, 1, 0, "length");
            }
            return new ModelResponse(
                    "{\"thought\":\"short\",\"toolCalls\":[],\"finalAnswer\":\"done\"}",
                    10, 10, 1, 0, "stop"
            );
        };

        AgentResult result = loop(model, List.of()).run("test");

        assertTrue(result.success());
        assertTrue(requests.get(1).getLast().content().contains("shorter complete JSON"));
        assertTrue(requests.get(1).getLast().content().contains("finish reason: length"));
    }

    @Test
    void executesToolAndReturnsResultToModel() throws Exception {
        Tool tool = new Tool() {
            @Override
            public String name() {
                return "echo";
            }

            @Override
            public String description() {
                return "Echo input.";
            }

            @Override
            public String argumentSchema() {
                return "{\"value\":\"string\"}";
            }

            @Override
            public boolean mutating() {
                return false;
            }

            @Override
            public ToolResult execute(JsonNode arguments) {
                return ToolResult.success(arguments.path("value").asText());
            }
        };
        RecordingModelClient model = new RecordingModelClient(
                """
                {"thought":"call","toolCalls":[{"name":"echo","arguments":{"value":"hello"}}],"finalAnswer":null}
                """,
                """
                {"thought":"done","toolCalls":[],"finalAnswer":"verified"}
                """
        );

        AgentResult result = loop(model, List.of(tool)).run("test");

        assertTrue(result.success());
        String toolFeedback = model.requests.get(1).getLast().content();
        assertTrue(toolFeedback.contains("\"success\":true"));
        assertTrue(toolFeedback.contains("hello"));
    }

    @Test
    void refusesFinalAnswerUntilAutomaticVerificationPasses() throws Exception {
        Tool mutatingTool = new Tool() {
            public String name() { return "change"; }
            public String description() { return "change"; }
            public String argumentSchema() { return "{}"; }
            public boolean mutating() { return true; }
            public ToolResult execute(JsonNode arguments) { return ToolResult.success("changed"); }
        };
        RecordingVerificationGate verification = new RecordingVerificationGate();
        RecordingModelClient model = new RecordingModelClient(
                """
                {"thought":"change","toolCalls":[{"name":"change","arguments":{}}],"finalAnswer":null}
                """,
                """
                {"thought":"done","toolCalls":[],"finalAnswer":"first attempt"}
                """,
                """
                {"thought":"fixed","toolCalls":[],"finalAnswer":"verified"}
                """
        );

        AgentResult result = loop(model, List.of(mutatingTool), verification).run("test");

        assertTrue(result.success());
        assertEquals("verified", result.output());
        assertEquals(2, verification.attempts);
        assertTrue(model.requests.get(2).getLast().content().contains("verification failed"));
    }

    @Test
    void approvedCommandDoesNotTriggerDuplicateAutomaticVerification() throws Exception {
        Tool command = new Tool() {
            public String name() { return "command"; }
            public String description() { return "command"; }
            public String argumentSchema() { return "{}"; }
            public boolean mutating() { return true; }
            public boolean requiresVerification() { return false; }
            public ToolResult execute(JsonNode arguments) { return ToolResult.success("exitCode=0"); }
        };
        RecordingVerificationGate verification = new RecordingVerificationGate();
        RecordingModelClient model = new RecordingModelClient(
                """
                {"thought":"run","toolCalls":[{"name":"command","arguments":{}}],"finalAnswer":null}
                """,
                """
                {"thought":"done","toolCalls":[],"finalAnswer":"passed"}
                """
        );

        AgentResult result = loop(model, List.of(command), verification).run("test");

        assertTrue(result.success());
        assertEquals(0, verification.attempts);
    }

    @Test
    void includesTheLatestAuthoritativeRuntimeSessionInEveryTask() throws Exception {
        AgentContext context = new AgentContext("", "", "");
        RecordingModelClient model = new RecordingModelClient(
                "{\"thought\":\"done\",\"toolCalls\":[],\"finalAnswer\":\"one\"}",
                "{\"thought\":\"done\",\"toolCalls\":[],\"finalAnswer\":\"two\"}"
        );
        AgentLoop loop = new AgentLoop(
                model,
                new DecisionParser(objectMapper),
                objectMapper,
                new ToolRegistry(List.of()),
                (tool, arguments) -> ApprovalDecision.ALLOW,
                new NoOpTraceWriter(),
                context,
                new NoOpStatusReporter(),
                new NoOpVerificationGate(),
                2,
                1
        );

        context.updateRuntimeSession("provider: openrouter\napproval: ask");
        loop.run("first");
        context.updateRuntimeSession("provider: openrouter\napproval: auto");
        loop.run("second");

        assertTrue(model.requests.get(0).getFirst().content().contains("approval: ask"));
        assertTrue(model.requests.get(1).getFirst().content().contains("approval: auto"));
        assertTrue(model.requests.get(1).getFirst().content().contains(
                "without listing or reading project files"
        ));
    }

    @Test
    void advertisesRuntimeNetworkAccessInsteadOfAssumingItIsUnavailable() throws Exception {
        Tool command = new RunCommandTool(
                new Workspace(Path.of(".")), Duration.ofSeconds(1), 1_000
        );
        RecordingModelClient model = new RecordingModelClient(
                "{\"thought\":\"done\",\"toolCalls\":[],\"finalAnswer\":\"done\"}"
        );

        AgentResult result = loop(model, List.of(command)).run("current weather");

        assertTrue(result.success());
        String systemPrompt = model.requests.getFirst().getFirst().content();
        assertTrue(systemPrompt.contains("Runtime capabilities (authoritative)"));
        assertTrue(systemPrompt.contains("network: inherited through run_command"));
        assertTrue(systemPrompt.contains("run_command: Run a shell command"));
    }

    @Test
    void abortsAfterBoundedConsecutiveUnknownTools() throws Exception {
        String unknown = """
                {"thought":"try","toolCalls":[{"name":"missing","arguments":{}}],"finalAnswer":null}
                """;
        RecordingModelClient model = new RecordingModelClient(unknown, unknown, unknown);

        AgentResult result = loop(model, List.of()).run("test");

        assertTrue(!result.success());
        assertTrue(result.output().contains("Unknown tool limit exceeded"));
        assertEquals(3, result.turns());
    }

    @Test
    void recordsTurnsTokensToolsAndCheckpointInMemory() throws Exception {
        Tool tool = new Tool() {
            public String name() { return "echo"; }
            public String description() { return "echo"; }
            public String argumentSchema() { return "{}"; }
            public boolean mutating() { return false; }
            public ToolResult execute(JsonNode arguments) { return ToolResult.success("ok"); }
        };
        RecordingModelClient model = new RecordingModelClient(
                "{\"thought\":\"call\",\"toolCalls\":[{\"name\":\"echo\",\"arguments\":{}}],\"finalAnswer\":null}",
                "{\"thought\":\"done\",\"toolCalls\":[],\"finalAnswer\":\"answer\"}"
        );
        RecordingMemory memory = new RecordingMemory();

        AgentResult result = loop(model, List.of(tool), new NoOpVerificationGate(), memory).run("goal");

        assertTrue(result.success());
        assertEquals(3, memory.messages.size());
        assertEquals(1, memory.tools);
        assertEquals(2, memory.responses);
        assertEquals(1, memory.checkpoints);
        assertEquals("answer", memory.answer);
    }

    @Test
    void compressionUsesDedicatedRequestAndSkillContextEntersSystemPrompt() throws Exception {
        RecordingModelClient model = new RecordingModelClient(
                "compact summary",
                "{\"thought\":\"done\",\"toolCalls\":[],\"finalAnswer\":\"answer\"}"
        );
        RecordingMemory memory = new RecordingMemory();
        memory.compress = true;

        AgentResult result = loop(model, List.of(), new NoOpVerificationGate(), memory).run("goal");

        assertTrue(result.success());
        assertTrue(model.requests.get(0).getLast().content().contains("compress this"));
        assertTrue(model.requests.get(1).getFirst().content().contains("active test skill"));
        assertEquals("compact summary", memory.summary);
    }

    private AgentLoop loop(ModelClient model, List<Tool> tools) {
        return loop(model, tools, new NoOpVerificationGate());
    }

    private AgentLoop loop(
            ModelClient model,
            List<Tool> tools,
            VerificationGate verificationGate
    ) {
        return loop(model, tools, verificationGate, AgentMemory.none());
    }

    private AgentLoop loop(
            ModelClient model,
            List<Tool> tools,
            VerificationGate verificationGate,
            AgentMemory memory
    ) {
        return new AgentLoop(
                model,
                new DecisionParser(objectMapper),
                objectMapper,
                new ToolRegistry(tools),
                (tool, arguments) -> ApprovalDecision.ALLOW,
                new NoOpTraceWriter(),
                new AgentContext("", "", ""),
                new NoOpStatusReporter(),
                verificationGate,
                4,
                2,
                null,
                memory
        );
    }

    private static final class RecordingMemory implements AgentMemory {
        private final List<ChatMessage> messages = new ArrayList<>();
        private int tools;
        private int responses;
        private int checkpoints;
        private String answer = "";
        private String summary = "";
        private boolean compress;

        @Override public void record(ChatMessage message, long prompt, long output) {
            messages.add(message);
        }
        @Override public void recordTool(String name, JsonNode arguments, String output) { tools++; }
        @Override public void addTokens(ModelResponse response) { responses++; }
        @Override public boolean shouldCompress() {
            boolean result = compress;
            compress = false;
            return result;
        }
        @Override public String compressionPrompt(List<ChatMessage> messages, String task) {
            return "compress this";
        }
        @Override public String storeSummary(String value) { summary = value; return value; }
        @Override public void checkpoint(List<ChatMessage> messages, String task) { checkpoints++; }
        @Override public String promptContext() { return "active test skill"; }
        @Override public void completed(String task, String value) { answer = value; }
    }

    private static final class RecordingVerificationGate implements VerificationGate {
        private boolean changed;
        private int attempts;

        @Override
        public void markChanged() {
            changed = true;
        }

        @Override
        public boolean required() {
            return changed;
        }

        @Override
        public VerificationResult verifyIfRequired() {
            if (!changed) {
                return VerificationResult.notRequired();
            }
            attempts++;
            if (attempts == 1) {
                return new VerificationResult(true, false, "mvn test", "exitCode=1");
            }
            changed = false;
            return new VerificationResult(true, true, "mvn test", "exitCode=0");
        }
    }

    private static final class RecordingModelClient implements ModelClient {
        private final ArrayDeque<String> responses;
        private final List<List<ChatMessage>> requests = new ArrayList<>();

        private RecordingModelClient(String... responses) {
            this.responses = new ArrayDeque<>(List.of(responses));
        }

        @Override
        public ModelResponse chat(List<ChatMessage> messages) {
            requests.add(List.copyOf(messages));
            return new ModelResponse(responses.removeFirst(), 0, 0, 0);
        }
    }
}
