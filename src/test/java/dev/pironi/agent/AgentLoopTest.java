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
import dev.pironi.tool.SubagentResult;
import dev.pironi.trace.NoOpTraceWriter;
import dev.pironi.status.NoOpStatusReporter;
import dev.pironi.verification.NoOpVerificationGate;
import dev.pironi.verification.VerificationGate;
import dev.pironi.verification.VerificationResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.nio.file.Path;
import java.time.Duration;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentLoopTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void systemPromptIncludesDynamicRegionalContextWithoutInventingLocation() throws Exception {
        RecordingModelClient model = new RecordingModelClient(
                "{\"thought\":\"done\",\"toolCalls\":[],\"finalAnswer\":\"ok\"}"
        );

        assertTrue(loop(model, List.of()).run("weather").success());
        String system = model.requests.getFirst().getFirst().content();
        assertTrue(system.contains("time-zone:"));
        assertTrue(system.contains("locale:"));
        assertTrue(system.contains("geographic-location: unknown"));
        assertTrue(system.contains("not proof"));
        assertTrue(system.contains("IP geolocation describes a network exit"));
        assertTrue(system.contains("claim high confidence"));
        assertTrue(system.contains("regular text file is not a symbolic link"));
    }

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
        assertTrue(model.requests.get(1).getLast().content().contains("valid json object"));
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
                {"thought":"call","finding":"probe","toolCalls":[{"name":"echo","arguments":{"value":"hello"}}],"finalAnswer":null}
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
    void convertsToolLinkageErrorIntoRecoverableToolFailure() throws Exception {
        Tool broken = new Tool() {
            public String name() { return "broken"; }
            public String description() { return "broken"; }
            public String argumentSchema() { return "{}"; }
            public boolean mutating() { return false; }
            public ToolResult execute(JsonNode arguments) { throw new NoClassDefFoundError("optional/module"); }
        };
        RecordingModelClient model = new RecordingModelClient(
                "{\"thought\":\"try\",\"finding\":\"probe\",\"toolCalls\":[{\"name\":\"broken\",\"arguments\":{}}],\"finalAnswer\":null}",
                "{\"thought\":\"report\",\"toolCalls\":[],\"finalAnswer\":\"Tool unavailable\"}"
        );
        AgentResult result = loop(model, List.of(broken)).run("test");
        assertTrue(result.success());
        assertTrue(model.requests.get(1).getLast().content().contains("NoClassDefFoundError"));
    }

    @Test
    void injectsCompletedSubagentResultIntoNextTurn() throws Exception {
        RecordingModelClient model = new RecordingModelClient(
                "{\"thought\":\"report\",\"toolCalls\":[],\"finalAnswer\":\"data received\"}"
        );
        java.util.concurrent.atomic.AtomicBoolean delivered = new java.util.concurrent.atomic.AtomicBoolean(false);
        dev.pironi.tool.SubagentGateway gateway = new dev.pironi.tool.SubagentGateway() {
            @Override
            public List<SubagentResult> awaitCompleted(java.time.Duration timeout) {
                return drainCompleted();
            }
            @Override
            public List<SubagentResult> drainCompleted() {
                if (!delivered.getAndSet(true)) {
                    return java.util.List.of(SubagentResult.completed(
                            "sub_1", "weather", "Sofia: 25C, sunny"
                    ));
                }
                return java.util.List.of();
            }
            @Override public List<String> runningHandles() { return java.util.List.of(); }
            @Override public int activeCount() { return delivered.get() ? 0 : 1; }
            @Override public void discardPending() { delivered.set(false); }
        };
        AgentLoop loopWithDrain = new AgentLoop(
                model,
                new DecisionParser(objectMapper),
                objectMapper,
                new ToolRegistry(java.util.List.of()),
                (tool, arguments) -> ApprovalDecision.ALLOW,
                new NoOpTraceWriter(),
                new AgentContext("", "", ""),
                new NoOpStatusReporter(),
                new NoOpVerificationGate(),
                4,
                2,
                null,
                AgentMemory.none(),
                null,
                gateway,
                java.time.Duration.ofSeconds(120),
                false
        );

        AgentResult result = loopWithDrain.run("test");

        assertTrue(result.success());
        // The drained subagent result must be injected before the first model call.
        // Search across ALL messages in the first request (not just the last one).
        assertTrue(model.requests.getFirst().stream()
                .anyMatch(m -> m.content().contains("[системно известие")),
                "[системно известие] must appear in any message of the first request");
        assertTrue(model.requests.getFirst().stream()
                .anyMatch(m -> m.content().contains("Sofia: 25C, sunny")));
        assertTrue(model.requests.getFirst().stream()
                .anyMatch(m -> m.content().contains("weather")));
    }

    @Test
    void failedBatchPreflightPreventsPartialMutation() throws Exception {
        java.util.concurrent.atomic.AtomicInteger executions = new java.util.concurrent.atomic.AtomicInteger();
        Tool valid = new Tool() {
            public String name() { return "valid_write"; }
            public String description() { return "valid"; }
            public String argumentSchema() { return "{}"; }
            public boolean mutating() { return true; }
            public ToolResult execute(JsonNode arguments) {
                executions.incrementAndGet();
                return ToolResult.success("changed");
            }
        };
        Tool invalid = new Tool() {
            public String name() { return "invalid_write"; }
            public String description() { return "invalid"; }
            public String argumentSchema() { return "{}"; }
            public boolean mutating() { return true; }
            public ToolResult validate(JsonNode arguments) {
                return ToolResult.failure("path escapes workspace");
            }
            public ToolResult execute(JsonNode arguments) {
                throw new AssertionError("must not execute");
            }
        };
        RecordingModelClient model = new RecordingModelClient(
                """
                {"thought":"batch","finding":"probe","toolCalls":[
                  {"name":"valid_write","arguments":{}},
                  {"name":"invalid_write","arguments":{}}
                ],"finalAnswer":null}
                """,
                """
                {"thought":"done","toolCalls":[],"finalAnswer":"reported"}
                """
        );

        AgentResult result = loop(model, List.of(valid, invalid)).run("test");

        assertTrue(result.success());
        assertEquals(0, executions.get());
        String feedback = model.requests.get(1).getLast().content();
        assertTrue(feedback.contains("failed_preflight"));
        assertTrue(feedback.contains("Not executed because another tool call failed preflight"));
    }

    @Test
    void systemPromptRequiresExactPathsAndScopedMoves() throws Exception {
        RecordingModelClient model = new RecordingModelClient(
                "{\"thought\":\"done\",\"toolCalls\":[],\"finalAnswer\":\"ok\"}"
        );

        loop(model, List.of()).run("create резултат.md");

        String prompt = model.requests.getFirst().getFirst().content();
        assertTrue(prompt.contains("filenames verbatim"));
        assertTrue(prompt.contains("use move_file for moves and renames"));
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
                {"thought":"change","finding":"probe","toolCalls":[{"name":"change","arguments":{}}],"finalAnswer":null}
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
                {"thought":"run","finding":"probe","toolCalls":[{"name":"command","arguments":{}}],"finalAnswer":null}
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
                {"thought":"try","finding":"probe","toolCalls":[{"name":"missing","arguments":{}}],"finalAnswer":null}
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
                "{\"thought\":\"call\",\"finding\":\"probe\",\"toolCalls\":[{\"name\":\"echo\",\"arguments\":{}}],\"finalAnswer\":null}",
                "{\"thought\":\"done\",\"toolCalls\":[],\"finalAnswer\":\"answer\"}"
        );
        RecordingMemory memory = new RecordingMemory();

        AgentResult result = loop(model, List.of(tool), new NoOpVerificationGate(), memory).run("goal");

        assertTrue(result.success());
        assertEquals(3, memory.messages.size());
        assertEquals(1, memory.tools);
        assertEquals(2, memory.responses);
        // one per turn plus the one on the way out, so a crash never loses the whole run
        assertEquals(3, memory.checkpoints);
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

    @Test
    void checkpointsAndMarksMemoryFailedWhenProviderThrows() {
        RecordingMemory memory = new RecordingMemory();
        ModelClient model = messages -> { throw new IOException("provider offline"); };

        try {
            loop(model, List.of(), new NoOpVerificationGate(), memory).run("goal");
        } catch (IOException expected) {
            assertEquals("provider offline", expected.getMessage());
        } catch (InterruptedException unexpected) {
            throw new AssertionError(unexpected);
        }

        assertEquals(1, memory.checkpoints);
        assertEquals(Boolean.FALSE, memory.finished);
    }

    @Test
    void compressionUsesPlainTextModelPath() throws Exception {
        RecordingMemory memory = new RecordingMemory();
        memory.compress = true;
        java.util.concurrent.atomic.AtomicBoolean plainCalled = new java.util.concurrent.atomic.AtomicBoolean();
        ModelClient model = new ModelClient() {
            @Override public ModelResponse chat(List<ChatMessage> messages) {
                return new ModelResponse(
                        "{\"thought\":\"done\",\"toolCalls\":[],\"finalAnswer\":\"ok\"}",
                        0, 0, 0
                );
            }
            @Override public ModelResponse chatText(List<ChatMessage> messages) {
                plainCalled.set(true);
                return new ModelResponse("plain summary", 0, 0, 0);
            }
        };

        AgentResult result = loop(model, List.of(), new NoOpVerificationGate(), memory).run("goal");

        assertTrue(result.success());
        assertTrue(plainCalled.get());
        assertEquals("plain summary", memory.summary);
    }

    @Test
    void warnsModelToExecuteAsTurnBudgetRunsLow() throws Exception {
        RecordingModelClient model = new RecordingModelClient(
                "{\"thought\":\"inspect\",\"finding\":\"probe\",\"toolCalls\":[{\"name\":\"echo\",\"arguments\":{}}],\"finalAnswer\":null}",
                "{\"thought\":\"done\",\"toolCalls\":[],\"finalAnswer\":\"ok\"}"
        );
        Tool echo = new Tool() {
            public String name() { return "echo"; }
            public String description() { return "echo"; }
            public String argumentSchema() { return "{}"; }
            public boolean mutating() { return false; }
            public ToolResult execute(JsonNode arguments) { return ToolResult.success("ok"); }
        };

        loop(model, List.of(echo)).run("create report.docx");

        assertTrue(model.requests.get(1).getLast().content().contains("3 turn(s) remain"));
        assertTrue(model.requests.get(1).getLast().content().contains("Execute the smallest complete solution"));
    }

    @Test
    void reportsSuccessfulMutationsWhenTurnLimitIsReached() throws Exception {
        Tool writer = new Tool() {
            public String name() { return "writer"; }
            public String description() { return "writer"; }
            public String argumentSchema() { return "{}"; }
            public boolean mutating() { return true; }
            public ToolResult execute(JsonNode arguments) { return ToolResult.success("Created outputs/report.csv"); }
        };
        String call = "{\"thought\":\"work\",\"finding\":\"probe\",\"toolCalls\":[{\"name\":\"writer\",\"arguments\":{}}],\"finalAnswer\":null}";
        RecordingModelClient model = new RecordingModelClient(call, call, call, call);

        AgentResult result = loop(model, List.of(writer)).run("create report");

        assertTrue(!result.success());
        assertTrue(result.output().contains("Successful mutating tool results"));
        assertTrue(result.output().contains("outputs/report.csv"));
    }

    @Test
    void usesOneFinalizationOnlyGraceTurnAfterSuccessfulMutation() throws Exception {
        Tool writer = new Tool() {
            public String name() { return "writer"; }
            public String description() { return "writer"; }
            public String argumentSchema() { return "{}"; }
            public boolean mutating() { return true; }
            public ToolResult execute(JsonNode arguments) { return ToolResult.success("Created outputs/report.md"); }
        };
        String call = "{\"thought\":\"work\",\"finding\":\"probe\",\"toolCalls\":[{\"name\":\"writer\",\"arguments\":{}}],\"finalAnswer\":null}";
        RecordingModelClient model = new RecordingModelClient(call, call, call, call,
                "{\"thought\":\"finalize\",\"toolCalls\":[],\"finalAnswer\":\"Created outputs/report.md\"}");

        AgentResult result = loop(model, List.of(writer)).run("create report");

        assertTrue(result.success(), result.output());
        assertEquals(5, result.turns());
        assertTrue(model.requests.get(4).getLast().content().contains("Finalization-only grace turn"));
        assertTrue(model.requests.get(4).getLast().content().contains("Do not call tools"));
    }

    @Test
    void usesFinalizationGraceAfterRepeatedToolFailure() throws Exception {
        Tool blocked = new Tool() {
            public String name() { return "blocked"; }
            public String description() { return "always fails"; }
            public String argumentSchema() { return "{}"; }
            public boolean mutating() { return false; }
            public ToolResult execute(JsonNode arguments) {
                return ToolResult.failure("Operation is outside the allowed workspace");
            }
        };
        String call = "{\"thought\":\"try\",\"finding\":\"probe\",\"toolCalls\":[{\"name\":\"blocked\",\"arguments\":{}}],\"finalAnswer\":null}";
        RecordingModelClient model = new RecordingModelClient(call, call, call, call,
                "{\"thought\":\"explain\",\"toolCalls\":[],\"finalAnswer\":\"Cannot modify the path outside the workspace.\"}");

        AgentResult result = loop(model, List.of(blocked)).run("edit external file");

        assertTrue(result.success(), result.output());
        assertEquals(5, result.turns());
        assertTrue(model.requests.get(4).getLast().content().contains("failed"));
        assertTrue(model.requests.get(4).getLast().content().contains("Do not call tools"));
    }

    @Test
    void collapsesShellVariationsOntoTheProgramTheyRun() throws Exception {
        assertEquals("run_command:osascript", AgentLoop.approachSignature(new ToolCall(
                "run_command",
                objectMapper.readTree("{\"command\":\"osascript -e 'tell app' | head -5\"}")
        )));
        assertEquals("run_command:osascript", AgentLoop.approachSignature(new ToolCall(
                "run_command",
                objectMapper.readTree("{\"command\":\"# probe\\nosascript -e 'other wording'\"}")
        )));
        assertEquals("run_command:sqlite3", AgentLoop.approachSignature(new ToolCall(
                "run_command",
                objectMapper.readTree("{\"command\":\"/usr/bin/sqlite3 store.db .tables\"}")
        )));
        assertEquals("read_file", AgentLoop.approachSignature(new ToolCall(
                "read_file", objectMapper.readTree("{\"path\":\"a.txt\"}")
        )));
    }

    @Test
    void reportsAnApproachThatStoppedProducingAnythingNew() throws Exception {
        Tool shell = new Tool() {
            public String name() { return "run_command"; }
            public String description() { return "shell"; }
            public String argumentSchema() { return "{}"; }
            public boolean mutating() { return false; }
            public ToolResult execute(JsonNode arguments) {
                return ToolResult.failure("execution error: Can't get every account");
            }
        };
        RecordingModelClient model = new RecordingModelClient(
                "{\"thought\":\"probe\",\"finding\":\"probe\",\"toolCalls\":[" + osascriptCalls(0, 4) + "],\"finalAnswer\":null}",
                "{\"thought\":\"again\",\"finding\":\"probe\",\"toolCalls\":[" + osascriptCalls(4, 2) + "],\"finalAnswer\":null}",
                "{\"thought\":\"stop\",\"toolCalls\":[],\"finalAnswer\":\"No mail is reachable that way.\"}"
        );

        AgentResult result = loop(model, List.of(shell)).run("summarise my mail");

        assertTrue(result.success(), result.output());
        String followUp = model.requests.get(2).getLast().content();
        assertTrue(followUp.contains("Approaches already exhausted (do not retry):"), followUp);
        assertTrue(followUp.contains("run_command:osascript"), followUp);
        assertTrue(followUp.contains("6 attempts, nothing new"), followUp);
    }

    private static String osascriptCalls(int from, int count) {
        StringBuilder calls = new StringBuilder();
        for (int index = from; index < from + count; index++) {
            if (index > from) calls.append(',');
            calls.append("{\"name\":\"run_command\",\"arguments\":{\"command\":\"osascript -e 'v")
                    .append(index).append("'\"}}");
        }
        return calls.toString();
    }

    @Test
    void keepsQuietWhileAnApproachStillYieldsNewResults() throws Exception {
        AtomicInteger counter = new AtomicInteger();
        Tool shell = new Tool() {
            public String name() { return "run_command"; }
            public String description() { return "shell"; }
            public String argumentSchema() { return "{}"; }
            public boolean mutating() { return false; }
            public ToolResult execute(JsonNode arguments) {
                return ToolResult.success(
                        "id|subject|received row " + counter.incrementAndGet()
                                + " of the mailbox export table");
            }
        };
        RecordingModelClient model = new RecordingModelClient(
                "{\"thought\":\"query\",\"finding\":\"probe\",\"toolCalls\":[" + sqliteCalls(0, 4) + "],\"finalAnswer\":null}",
                "{\"thought\":\"more\",\"finding\":\"probe\",\"toolCalls\":[" + sqliteCalls(4, 3) + "],\"finalAnswer\":null}",
                "{\"thought\":\"done\",\"toolCalls\":[],\"finalAnswer\":\"Seven rows read.\"}"
        );

        AgentResult result = loop(model, List.of(shell)).run("read the store");

        assertTrue(result.success(), result.output());
        assertTrue(!model.requests.get(2).getLast().content().contains("already exhausted"));
    }

    private static String sqliteCalls(int from, int count) {
        StringBuilder calls = new StringBuilder();
        for (int index = from; index < from + count; index++) {
            if (index > from) calls.append(',');
            calls.append("{\"name\":\"run_command\",\"arguments\":{\"command\":\"sqlite3 q")
                    .append(index).append("\"}}");
        }
        return calls.toString();
    }

    @Test
    void runsOnlyTheFirstFourToolCallsAndSaysSoAboutTheRest() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        Tool probe = new Tool() {
            public String name() { return "read_file"; }
            public String description() { return "read"; }
            public String argumentSchema() { return "{}"; }
            public boolean mutating() { return false; }
            public ToolResult execute(JsonNode arguments) {
                return ToolResult.success("line " + executions.incrementAndGet());
            }
        };
        StringBuilder calls = new StringBuilder();
        for (int index = 0; index < 9; index++) {
            if (index > 0) calls.append(',');
            calls.append("{\"name\":\"read_file\",\"arguments\":{\"path\":\"f")
                    .append(index).append("\"}}");
        }
        RecordingModelClient model = new RecordingModelClient(
                "{\"thought\":\"batch\",\"finding\":\"probe\",\"toolCalls\":[" + calls + "],\"finalAnswer\":null}",
                "{\"thought\":\"done\",\"toolCalls\":[],\"finalAnswer\":\"Read them.\"}"
        );

        AgentResult result = loop(model, List.of(probe)).run("read nine files");

        assertTrue(result.success(), result.output());
        assertEquals(4, executions.get());
        String followUp = model.requests.get(1).getLast().content();
        assertTrue(followUp.contains("5 further tool calls in that batch were not run"), followUp);
    }

    @Test
    void replaysFindingsSoReadOnlyWorkIsNotRepeated() throws Exception {
        Tool probe = new Tool() {
            public String name() { return "read_file"; }
            public String description() { return "read"; }
            public String argumentSchema() { return "{}"; }
            public boolean mutating() { return false; }
            public ToolResult execute(JsonNode arguments) {
                return ToolResult.success("binary junk");
            }
        };
        RecordingModelClient model = new RecordingModelClient(
                "{\"thought\":\"look\",\"finding\":\"Outlook.sqlite Mail table is empty\","
                        + "\"toolCalls\":[{\"name\":\"read_file\",\"arguments\":{\"path\":\"a\"}}],"
                        + "\"finalAnswer\":null}",
                "{\"thought\":\"look again\",\"finding\":\"olk15Main files are OLE binaries\","
                        + "\"toolCalls\":[{\"name\":\"read_file\",\"arguments\":{\"path\":\"b\"}}],"
                        + "\"finalAnswer\":null}",
                "{\"thought\":\"stop\",\"toolCalls\":[],\"finalAnswer\":\"Nothing readable.\"}"
        );

        AgentResult result = loop(model, List.of(probe)).run("find the mail store");

        assertTrue(result.success(), result.output());
        String last = model.requests.get(2).getLast().content();
        assertTrue(last.contains("Established so far (do not re-derive):"), last);
        assertTrue(last.contains("- Outlook.sqlite Mail table is empty"), last);
        assertTrue(last.contains("- olk15Main files are OLE binaries"), last);
    }

    @Test
    void recordsNothingWhenTheModelOmitsTheFinding() throws Exception {
        Tool probe = new Tool() {
            public String name() { return "read_file"; }
            public String description() { return "read"; }
            public String argumentSchema() { return "{}"; }
            public boolean mutating() { return false; }
            public ToolResult execute(JsonNode arguments) { return ToolResult.success("bytes"); }
        };
        RecordingModelClient model = new RecordingModelClient(
                "{\"thought\":\"Step 1: let me look around and see what is here.\","
                        + "\"toolCalls\":[{\"name\":\"read_file\",\"arguments\":{\"path\":\"a\"}}],"
                        + "\"finalAnswer\":null}",
                "{\"thought\":\"done\",\"toolCalls\":[],\"finalAnswer\":\"Nothing readable.\"}"
        );

        AgentResult result = loop(model, List.of(probe)).run("find the mail store");

        assertTrue(result.success(), result.output());
        String followUp = model.requests.get(1).getLast().content();
        assertTrue(followUp.contains("finding was missing"), followUp);
        assertTrue(!followUp.contains("Established so far"), followUp);
        assertTrue(!followUp.contains("Step 1"), followUp);
    }

    @Test
    void aSpentApproachStaysListedAfterALaterIncidentalReply() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Tool shell = new Tool() {
            public String name() { return "run_command"; }
            public String description() { return "shell"; }
            public String argumentSchema() { return "{}"; }
            public boolean mutating() { return false; }
            public ToolResult execute(JsonNode arguments) {
                // six dead attempts, then one long reply that used to revive the approach
                return calls.incrementAndGet() <= 6
                        ? ToolResult.failure("execution error: Can't get every account")
                        : ToolResult.success("version 16.111.3 build 2026 with a long descriptive tail");
            }
        };
        RecordingModelClient model = new RecordingModelClient(
                "{\"thought\":\"probe\",\"toolCalls\":[" + osascriptCalls(0, 4) + "],\"finalAnswer\":null}",
                "{\"thought\":\"again\",\"toolCalls\":[" + osascriptCalls(4, 2) + "],\"finalAnswer\":null}",
                "{\"thought\":\"once more\",\"toolCalls\":[" + osascriptCalls(6, 1) + "],\"finalAnswer\":null}",
                "{\"thought\":\"stop\",\"toolCalls\":[],\"finalAnswer\":\"Not reachable that way.\"}"
        );

        AgentResult result = loop(model, List.of(shell)).run("summarise my mail");

        assertTrue(result.success(), result.output());
        String afterRevival = model.requests.get(3).getLast().content();
        assertTrue(afterRevival.contains("Approaches already exhausted (do not retry):"), afterRevival);
        assertTrue(afterRevival.contains("run_command:osascript"), afterRevival);
    }

    @Test
    void stopsRunningAnApproachThatKeepsReturningTheSameAnswer() throws Exception {
        AtomicInteger executed = new AtomicInteger();
        Tool shell = new Tool() {
            public String name() { return "run_command"; }
            public String description() { return "shell"; }
            public String argumentSchema() { return "{}"; }
            public boolean mutating() { return false; }
            public ToolResult execute(JsonNode arguments) {
                executed.incrementAndGet();
                // different arguments every time, identical answer every time
                return ToolResult.success("0");
            }
        };
        RecordingModelClient model = new RecordingModelClient(
                "{\"thought\":\"probe\",\"toolCalls\":[" + osascriptCalls(0, 4) + "],\"finalAnswer\":null}",
                "{\"thought\":\"again\",\"toolCalls\":[" + osascriptCalls(4, 4) + "],\"finalAnswer\":null}",
                "{\"thought\":\"once more\",\"toolCalls\":[" + osascriptCalls(8, 4) + "],\"finalAnswer\":null}",
                "{\"thought\":\"stop\",\"toolCalls\":[],\"finalAnswer\":\"Not reachable that way.\"}"
        );

        AgentResult result = loop(model, List.of(shell)).run("summarise my mail");

        assertTrue(result.success(), result.output());
        // the block stops the tool actually running, not just advises against it
        assertTrue(executed.get() < 12, "executed " + executed.get() + " times");
        String afterBlock = model.requests.get(3).getLast().content();
        assertTrue(afterBlock.contains("is blocked after"), afterBlock);
        assertTrue(afterBlock.contains("use a different kind of approach"), afterBlock);
    }

    @Test
    void flagsAnEmptyResultAsUnprovenAbsence() throws Exception {
        Tool probe = new Tool() {
            public String name() { return "run_command"; }
            public String description() { return "shell"; }
            public String argumentSchema() { return "{}"; }
            public boolean mutating() { return false; }
            public ToolResult execute(JsonNode arguments) { return ToolResult.success("exitCode=0\n0\n"); }
        };
        RecordingModelClient model = new RecordingModelClient(
                "{\"thought\":\"count\",\"finding\":\"probe\","
                        + "\"toolCalls\":[{\"name\":\"run_command\",\"arguments\":{\"command\":\"osascript -e x\"}}],"
                        + "\"finalAnswer\":null}",
                "{\"thought\":\"done\",\"toolCalls\":[],\"finalAnswer\":\"Reported.\"}"
        );

        AgentResult result = loop(model, List.of(probe)).run("count my mail");

        assertTrue(result.success(), result.output());
        String followUp = model.requests.get(1).getLast().content();
        assertTrue(followUp.contains("returned no data"), followUp);
        assertTrue(followUp.contains("not evidence of absence"), followUp);
    }

    @Test
    void wallsOffSinglePurposeProgramsAndAimedInterpretersButNotBareOnes() {
        assertTrue(AgentLoop.blockable("run_command:osascript"));
        assertTrue(AgentLoop.blockable("run_command:sqlite3"));
        assertTrue(AgentLoop.blockable("read_file"));
        // a bare interpreter is a capability, not an approach
        assertTrue(!AgentLoop.blockable("run_command:python3"));
        assertTrue(!AgentLoop.blockable("run_command:bash"));
        // aimed at something specific, it is an approach again
        assertTrue(AgentLoop.blockable("run_command:python3:sqlite3"));
        assertTrue(AgentLoop.blockable("run_command:bash:osascript"));
    }

    @Test
    void separatesWhatAnInterpreterIsReachingFor() throws Exception {
        assertEquals("run_command:python3:sqlite3", AgentLoop.approachSignature(new ToolCall(
                "run_command",
                objectMapper.readTree("{\"command\":\"python3 -c \\\"import sqlite3; q()\\\"\"}")
        )));
        assertEquals("run_command:python3:glob+gzip", AgentLoop.approachSignature(new ToolCall(
                "run_command",
                objectMapper.readTree("{\"command\":\"python3 -c \\\"import gzip, glob\\\"\"}")
        )));
        assertEquals("run_command:python3:report.py", AgentLoop.approachSignature(new ToolCall(
                "run_command",
                objectMapper.readTree("{\"command\":\"python3 scripts/report.py --days 2\"}")
        )));
        // single-purpose programs keep the plain signature
        assertEquals("run_command:sqlite3", AgentLoop.approachSignature(new ToolCall(
                "run_command", objectMapper.readTree("{\"command\":\"sqlite3 store.db .tables\"}")
        )));
    }

    @Test
    void startsFromWhatEarlierRunsEstablished() throws Exception {
        Tool probe = new Tool() {
            public String name() { return "read_file"; }
            public String description() { return "read"; }
            public String argumentSchema() { return "{}"; }
            public boolean mutating() { return false; }
            public ToolResult execute(JsonNode arguments) { return ToolResult.success("bytes"); }
        };
        List<String> handedOn = new ArrayList<>();
        AgentMemory memory = new AgentMemory() {
            @Override public List<String> priorFindings() {
                return List.of("OSA logs are PII-redacted");
            }
            @Override public void rememberFindings(List<String> findings) {
                handedOn.clear();
                handedOn.addAll(findings);
            }
        };
        RecordingModelClient model = new RecordingModelClient(
                "{\"thought\":\"look\",\"finding\":\"calendar store.json is readable\","
                        + "\"toolCalls\":[{\"name\":\"read_file\",\"arguments\":{\"path\":\"a\"}}],"
                        + "\"finalAnswer\":null}",
                "{\"thought\":\"done\",\"toolCalls\":[],\"finalAnswer\":\"Reported.\"}"
        );

        AgentResult result = loop(model, List.of(probe), new NoOpVerificationGate(), memory)
                .run("summarise my mail");

        assertTrue(result.success(), result.output());
        String followUp = model.requests.get(1).getLast().content();
        assertTrue(followUp.contains("- OSA logs are PII-redacted"), followUp);
        assertTrue(followUp.contains("- calendar store.json is readable"), followUp);
        assertEquals(2, handedOn.size());
    }

    @Test
    void inheritedFindingsAreNotTrimmedAwayByThisRunsOwn() {
        List<String> findings = new ArrayList<>();
        findings.add("calendar store.json is readable");
        findings.add("Outlook.sqlite is empty");
        int pinned = findings.size();

        for (int index = 0; index < 60; index++) {
            AgentLoop.recordFinding(findings, "later fact " + index, pinned);
        }

        assertEquals(AgentLoop.MAX_FINDINGS, findings.size());
        assertEquals("calendar store.json is readable", findings.get(0));
        assertEquals("Outlook.sqlite is empty", findings.get(1));
        assertEquals("later fact 59", findings.getLast());
    }

    @Test
    void saysSoWhenTheSameConclusionKeepsComingBack() throws Exception {
        Tool probe = new Tool() {
            public String name() { return "read_file"; }
            public String description() { return "read"; }
            public String argumentSchema() { return "{}"; }
            public boolean mutating() { return false; }
            public ToolResult execute(JsonNode arguments) { return ToolResult.success("bytes"); }
        };
        String stuck = "{\"thought\":\"again\",\"finding\":\"OSA logs are PII-redacted\","
                + "\"toolCalls\":[{\"name\":\"read_file\",\"arguments\":{\"path\":\"a\"}}],"
                + "\"finalAnswer\":null}";
        RecordingModelClient model = new RecordingModelClient(
                stuck, stuck, stuck, stuck, stuck,
                "{\"thought\":\"stop\",\"toolCalls\":[],\"finalAnswer\":\"Not reachable.\"}"
        );

        AgentResult result = loop(model, List.of(probe), new NoOpVerificationGate(),
                AgentMemory.none(), 8).run("summarise my mail");

        assertTrue(result.success(), result.output());
        String later = model.requests.get(5).getLast().content();
        assertTrue(later.contains("turns running. Nothing has been learned"), later);
    }

    @Test
    void aFullyInheritedLedgerStillAcceptsWhatThisRunLearns() {
        List<String> findings = new ArrayList<>();
        for (int index = 0; index < AgentLoop.MAX_FINDINGS; index++) {
            findings.add("inherited " + index);
        }
        int pinned = findings.size();

        AgentLoop.recordFinding(findings, "learned right now", pinned);

        assertEquals(AgentLoop.MAX_FINDINGS, findings.size());
        assertEquals("learned right now", findings.getLast());
        // the oldest inherited entry gave way, not the new one
        assertEquals("inherited 1", findings.get(0));
    }

    @Test
    void inheritedFindingsAreOfferedForRecheckingNotAsSettled() {
        String ledger = AgentLoop.findingsLedger(
                List.of("OSA logs are PII-redacted", "calendar store.json is readable"), 1);

        assertTrue(ledger.contains("Established by earlier runs here"), ledger);
        assertTrue(ledger.contains("re-check"), ledger);
        assertTrue(ledger.contains("Established so far (do not re-derive):"), ledger);
    }

    @Test
    void findingsAreDeduplicatedAndCapped() {
        List<String> findings = new ArrayList<>();
        AgentLoop.recordFinding(findings, "mail store is HxStore.hxd");
        AgentLoop.recordFinding(findings, "  mail store is HxStore.hxd  ");
        AgentLoop.recordFinding(findings, "");
        AgentLoop.recordFinding(findings, null);
        assertEquals(1, findings.size());

        for (int index = 0; index < 60; index++) {
            AgentLoop.recordFinding(findings, "fact " + index);
        }
        assertEquals(AgentLoop.MAX_FINDINGS, findings.size());
        assertEquals("fact 59", findings.getLast());
        assertEquals("", AgentLoop.findingsLedger(new ArrayList<>()));
    }

    @Test
    void truncationKeepsTheSystemPromptAndTheTask() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("system"));
        messages.add(ChatMessage.user("export the mailbox and report hours per project"));
        for (int i = 0; i < 60; i++) {
            messages.add(ChatMessage.assistant("step " + i));
        }

        AgentLoop.truncateHistory(messages);

        assertEquals(40, messages.size());
        assertEquals("system", messages.getFirst().content());
        assertEquals("export the mailbox and report hours per project", messages.get(1).content());
        assertEquals("step 59", messages.getLast().content());
    }

    @Test
    void truncationWithoutATaskStillKeepsTheSystemPrompt() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("system"));
        for (int i = 0; i < 60; i++) {
            messages.add(ChatMessage.assistant("step " + i));
        }

        AgentLoop.truncateHistory(messages);

        assertEquals(40, messages.size());
        assertEquals("system", messages.getFirst().content());
        assertEquals("step 59", messages.getLast().content());
    }

    @Test
    void compressionKeepsFinishedWorkOutOfReach() throws Exception {
        Tool exportTool = new Tool() {
            public String name() { return "export"; }
            public String description() { return "export"; }
            public String argumentSchema() { return "{}"; }
            public boolean mutating() { return true; }
            public ToolResult execute(JsonNode arguments) { return ToolResult.success("mail.txt"); }
        };
        RecordingModelClient model = new RecordingModelClient(
                """
                {"thought":"export","finding":"probe","toolCalls":[{"name":"export","arguments":{}}],"finalAnswer":null}
                """,
                "compact summary",
                """
                {"thought":"done","toolCalls":[],"finalAnswer":"answer"}
                """
        );
        AgentMemory memory = new AgentMemory() {
            private int calls;
            @Override public boolean shouldCompress() { return ++calls == 2; }
            @Override public String compressionPrompt(List<ChatMessage> messages, String task) {
                return "compress this";
            }
            @Override public String storeSummary(String summary) { return summary; }
        };

        AgentResult result = loop(model, List.of(exportTool), new NoOpVerificationGate(), memory)
                .run("weekly report");

        assertTrue(result.success());
        String afterCompression = model.requests.getLast().get(1).content();
        assertTrue(afterCompression.contains("compact summary"));
        assertTrue(afterCompression.contains("Already completed in this session"));
        assertTrue(afterCompression.contains("export: mail.txt"));
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
        return loop(model, tools, verificationGate, memory, 4);
    }

    private AgentLoop loop(
            ModelClient model,
            List<Tool> tools,
            VerificationGate verificationGate,
            AgentMemory memory,
            int maxTurns
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
                maxTurns,
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
        private Boolean finished;

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
        @Override public void finished(boolean success) { finished = success; }
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
