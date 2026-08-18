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
                5, 2, null, AgentMemory.none(), null, null,
                java.time.Duration.ofSeconds(5), false
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
