package dev.pironi.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.pironi.model.ChatMessage;
import dev.pironi.model.ModelClient;
import dev.pironi.model.ModelResponse;
import dev.pironi.safety.ApprovalDecision;
import dev.pironi.status.NoOpStatusReporter;
import dev.pironi.tool.Tool;
import dev.pironi.tool.ToolRegistry;
import dev.pironi.tool.ToolResult;
import dev.pironi.trace.NoOpTraceWriter;
import dev.pironi.verification.NoOpVerificationGate;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** --swarm: the read-only calls at the head of a batch run at once; writes keep their order. */
class AgentLoopParallelReadsTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static String call(String name, String path) {
        return "{\"name\":\"" + name + "\",\"arguments\":{\"path\":\"" + path + "\"}}";
    }

    private static String turn(String... calls) {
        return "{\"thought\":\"t\",\"finding\":\"f\",\"toolCalls\":[" + String.join(",", calls) + "],\"finalAnswer\":null}";
    }

    private static final String DONE = "{\"thought\":\"t\",\"toolCalls\":[],\"finalAnswer\":\"done\"}";

    /** read_file that only returns once all three reads are inside it at the same time. */
    private static Tool meetingRead(CyclicBarrier meeting, List<String> log) {
        return new Tool() {
            @Override public String name() { return "read_file"; }
            @Override public String description() { return "read"; }
            @Override public String argumentSchema() { return "{\"path\":\"..\"}"; }
            @Override public boolean mutating() { return false; }
            @Override public ToolResult execute(JsonNode arguments) {
                try {
                    meeting.await(2, TimeUnit.SECONDS);
                } catch (Exception e) {
                    return ToolResult.failure("ran alone: " + e.getClass().getSimpleName());
                }
                log.add("read " + arguments.path("path").asText());
                return ToolResult.success("content of " + arguments.path("path").asText());
            }
        };
    }

    private static Tool loggingTool(String name, boolean mutating, List<String> log) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return name; }
            @Override public String argumentSchema() { return "{\"path\":\"..\"}"; }
            @Override public boolean mutating() { return mutating; }
            @Override public ToolResult execute(JsonNode arguments) {
                log.add(name + " " + arguments.path("path").asText());
                return ToolResult.success(name + " ok " + arguments.path("path").asText());
            }
        };
    }

    @Test
    void readsAtTheHeadOfABatchRunAtOnceAndReportInOrder() throws Exception {
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        Scripted model = new Scripted(turn(call("read_file", "a"), call("read_file", "b"), call("read_file", "c")), DONE);
        AgentLoop loop = loop(model, List.of(meetingRead(new CyclicBarrier(3), log)), true);

        assertTrue(loop.run("read three").success());
        String results = model.requests.get(1).getLast().content();
        assertTrue(!results.contains("ran alone"), "the reads did not run at once: " + results);
        assertTrue(results.indexOf("content of a") < results.indexOf("content of b")
                && results.indexOf("content of b") < results.indexOf("content of c"), results);
    }

    @Test
    void withoutTheFlagReadsRunOneByOne() throws Exception {
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        Scripted model = new Scripted(turn(call("read_file", "a"), call("read_file", "b")), DONE);
        AgentLoop loop = loop(model, List.of(meetingRead(new CyclicBarrier(2), log)), false);

        loop.run("read two");
        assertTrue(model.requests.get(1).getLast().content().contains("ran alone"),
                "reads overlapped without --swarm");
    }

    @Test
    void aReadAfterAWriteSeesTheWrite() throws Exception {
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        Scripted model = new Scripted(turn(call("list_files", "x"), call("write_file", "y"), call("list_files", "y")), DONE);
        AgentLoop loop = loop(model, List.of(loggingTool("list_files", false, log), loggingTool("write_file", true, log)), true);

        assertTrue(loop.run("write then read").success());
        assertEquals(List.of("list_files x", "write_file y", "list_files y"), log);
    }

    private AgentLoop loop(ModelClient model, List<Tool> tools, boolean parallel) {
        return new AgentLoop(model, new DecisionParser(objectMapper), objectMapper, new ToolRegistry(tools),
                (tool, arguments) -> ApprovalDecision.ALLOW, new NoOpTraceWriter(), new AgentContext("", "", ""),
                new NoOpStatusReporter(), new NoOpVerificationGate(), 5, 2,
                AgentLoop.Settings.defaults().withParallelReads(parallel));
    }

    private static final class Scripted implements ModelClient {
        private final ArrayDeque<String> responses;
        final List<List<ChatMessage>> requests = new ArrayList<>();

        Scripted(String... resp) {
            responses = new ArrayDeque<>(List.of(resp));
        }

        @Override
        public ModelResponse chat(List<ChatMessage> messages) {
            requests.add(new ArrayList<>(messages));
            return new ModelResponse(responses.size() > 1 ? responses.poll() : responses.peek(), 0, 0, 0);
        }
    }
}
