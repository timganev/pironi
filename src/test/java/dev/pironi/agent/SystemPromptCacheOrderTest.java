package dev.pironi.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.pironi.model.ChatMessage;
import dev.pironi.model.ModelClient;
import dev.pironi.model.ModelResponse;
import dev.pironi.safety.ApprovalDecision;
import dev.pironi.status.NoOpStatusReporter;
import dev.pironi.tool.ToolRegistry;
import dev.pironi.trace.NoOpTraceWriter;
import dev.pironi.verification.NoOpVerificationGate;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test: volatile system-prompt sections must come after the stable ones.
 *
 * <p>Servers cache prompts by token prefix, so the first section that differs between two requests
 * invalidates everything after it. "Current time and regional context" embeds
 * {@code ZonedDateTime.now()} and therefore changes on every single call; placing it before the
 * stable blocks discarded the whole prompt — including the project CLAUDE.md — on every turn.
 * Measured against vLLM with a 13k-token prompt on 2026-08-18: volatile-first stayed at 6.8 tok/s
 * end-to-end on every turn, volatile-last reached 21.1 tok/s from the second turn on.
 */
class SystemPromptCacheOrderTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void volatileSectionsComeAfterStableOnes() throws Exception {
        CapturingModel model = new CapturingModel();
        AgentContext context = new AgentContext("soul-marker", "user-marker", "claude-md-marker");
        // appendContext skips blank sections, so the runtime session needs a value to show up here
        context.updateRuntimeSession("provider: test\nmodel: test-model");
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
                5, 2,
                AgentLoop.Settings.defaults()
        );

        assertTrue(loop.run("task").success());
        String prompt = model.requests.getFirst().getFirst().content();

        int capabilities = prompt.indexOf("Runtime capabilities (authoritative)");
        int soul = prompt.indexOf("Identity from SOUL.md");
        int user = prompt.indexOf("User profile from USER.md");
        int project = prompt.indexOf("Project instructions from CLAUDE.md");
        int session = prompt.indexOf("Current runtime session (authoritative)");
        int time = prompt.indexOf("Current time and regional context");

        assertTrue(capabilities >= 0 && soul >= 0 && user >= 0
                        && project >= 0 && session >= 0 && time >= 0,
                "all context sections must be present");

        int lastStable = Math.max(Math.max(capabilities, soul), Math.max(user, project));
        assertTrue(session > lastStable,
                "runtime session must come after every stable section, or prefix caching breaks");
        assertTrue(time > lastStable,
                "current time must come after every stable section, or prefix caching breaks");
    }

    /**
     * A follow-up task in the same session must not rewrite the system message: it heads every
     * request, so changing it (the clock moves, another skill is picked) made the server re-read
     * the whole conversation on each new message. Measured on vLLM 2026-10-25: the prefix-cache
     * hit rate fell to 0 for every follow-up in an 11-task Pironi session.
     */
    @Test
    void followUpTaskKeepsTheSystemMessageAndCarriesTheVolatileSections() throws Exception {
        CapturingModel model = new CapturingModel();
        CarryingMemory memory = new CarryingMemory();
        AgentContext context = new AgentContext("soul-marker", "user-marker", "claude-md-marker");
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
                5, 2,
                AgentLoop.Settings.defaults().withMemory(memory)
        );

        memory.skill = "first-skill-marker";
        assertTrue(loop.run("first task").success());
        memory.skill = "second-skill-marker";
        Thread.sleep(1100); // the time section has second resolution
        assertTrue(loop.run("second task").success());

        List<ChatMessage> first = model.requests.getFirst();
        List<ChatMessage> second = model.requests.getLast();
        assertEquals(first.getFirst().content(), second.getFirst().content(),
                "the system message must stay byte-identical across tasks");
        String followUp = second.getLast().content();
        assertTrue(followUp.startsWith("second task"));
        assertTrue(followUp.contains(AgentLoop.CONTEXT_UPDATE_HEADING));
        assertTrue(followUp.contains("second-skill-marker"));
        assertTrue(followUp.contains("Current time and regional context"));
        assertFalse(second.getFirst().content().contains("second-skill-marker"));
    }

    /**
     * The same, across processes: {@code --continue} reads the conversation back from a checkpoint,
     * and the checkpoint masks secret-looking text, so the stored system message no longer matched
     * the live one ("Bearer PIRONI_API_KEY" in http_get came back as "Bearer [REDACTED]"). It was
     * rebuilt with a new clock on every step and the server re-read the whole history. Measured on
     * vLLM 2026-10-27: 37-41% of a 20k-token prompt cached on a one-request step, the system
     * prompt's stable part and nothing after it.
     */
    @Test
    void resumedSessionKeepsTheLiveSystemMessageAlthoughTheCheckpointMaskedIt() throws Exception {
        CapturingModel model = new CapturingModel();
        PersistingMemory memory = new PersistingMemory();
        AgentContext context = new AgentContext("soul-marker", "user-marker", "claude-md-marker");
        dev.pironi.tool.Tool tool = describedTool("http_get",
                "Auth headers use placeholders (e.g. \"Bearer PIRONI_API_KEY\").");
        AgentLoop.Settings settings = AgentLoop.Settings.defaults().withMemory(memory);
        AgentLoop first = new AgentLoop(model, new DecisionParser(objectMapper), objectMapper,
                new ToolRegistry(List.of(tool)), (t, a) -> ApprovalDecision.ALLOW, new NoOpTraceWriter(),
                context, new NoOpStatusReporter(), new NoOpVerificationGate(), 5, 2, settings);
        assertTrue(first.run("first task").success());
        assertTrue(memory.carried.getFirst().content().contains("Bearer [REDACTED]"),
                "the stand-in store must mask like the real one, or this test proves nothing");

        Thread.sleep(1100); // the time section has second resolution
        AgentLoop resumed = new AgentLoop(model, new DecisionParser(objectMapper), objectMapper,
                new ToolRegistry(List.of(tool)), (t, a) -> ApprovalDecision.ALLOW, new NoOpTraceWriter(),
                context, new NoOpStatusReporter(), new NoOpVerificationGate(), 5, 2, settings);
        assertTrue(resumed.run("second task").success());

        List<ChatMessage> before = model.requests.getFirst();
        List<ChatMessage> after = model.requests.getLast();
        assertEquals(before.getFirst().content(), after.getFirst().content(),
                "the resumed request must start with the system message the server already cached");
        assertTrue(after.getLast().content().contains(AgentLoop.CONTEXT_UPDATE_HEADING));
    }

    /** Tools reach the registry in a different order on every start; the prompt must not follow it. */
    @Test
    void toolListIsSortedByName() throws Exception {
        CapturingModel model = new CapturingModel();
        AgentLoop loop = new AgentLoop(
                model,
                new DecisionParser(objectMapper),
                objectMapper,
                new ToolRegistry(List.of(namedTool("zeta_tool"), namedTool("alpha_tool"))),
                (tool, arguments) -> ApprovalDecision.ALLOW,
                new NoOpTraceWriter(),
                new AgentContext("", "", ""),
                new NoOpStatusReporter(),
                new NoOpVerificationGate(),
                5, 2,
                AgentLoop.Settings.defaults()
        );

        assertTrue(loop.run("task").success());
        String prompt = model.requests.getFirst().getFirst().content();
        assertTrue(prompt.indexOf("- alpha_tool:") < prompt.indexOf("- zeta_tool:"));
    }

    private static dev.pironi.tool.Tool namedTool(String name) {
        return new dev.pironi.tool.Tool() {
            public String name() { return name; }
            public String description() { return name; }
            public String argumentSchema() { return "{}"; }
            public boolean mutating() { return false; }
            public dev.pironi.tool.ToolResult execute(JsonNode arguments) { return dev.pironi.tool.ToolResult.success(""); }
        };
    }

    private static dev.pironi.tool.Tool describedTool(String name, String description) {
        return new dev.pironi.tool.Tool() {
            public String name() { return name; }
            public String description() { return description; }
            public String argumentSchema() { return "{}"; }
            public boolean mutating() { return false; }
            public dev.pironi.tool.ToolResult execute(JsonNode arguments) { return dev.pironi.tool.ToolResult.success(""); }
        };
    }

    /** Stands in for the checkpoint on disk: what comes back is masked, as SessionStore writes it. */
    private static final class PersistingMemory implements AgentMemory {
        List<ChatMessage> carried = List.of();

        @Override public List<ChatMessage> begin(String task) { return carried; }
        @Override public void checkpoint(List<ChatMessage> messages, String task) {
            carried = messages.stream()
                    .map(m -> new ChatMessage(m.role(), dev.pironi.session.SecretRedactor.redact(m.content())))
                    .toList();
        }
        @Override public String persistedForm(String text) { return dev.pironi.session.SecretRedactor.redact(text); }
    }

    private static final class CarryingMemory implements AgentMemory {
        String skill = "";
        List<ChatMessage> carried = List.of();

        @Override public List<ChatMessage> begin(String task) { return carried; }
        @Override public void checkpoint(List<ChatMessage> messages, String task) { carried = List.copyOf(messages); }
        @Override public String promptContext() { return skill; }
    }

    private static final class CapturingModel implements ModelClient {
        final List<List<ChatMessage>> requests = new ArrayList<>();

        @Override
        public ModelResponse chat(List<ChatMessage> messages) {
            requests.add(new ArrayList<>(messages));
            return new ModelResponse(
                    "{\"thought\":\"t\",\"toolCalls\":[],\"finalAnswer\":\"ok\"}", 0, 0, 0, 0);
        }
    }
}
