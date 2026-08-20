package dev.pironi.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.pironi.model.ModelResponse;
import dev.pironi.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonlTraceWriterTest {
    @TempDir Path root;

    @Test
    void recordsProviderAttemptsFallbackAndErrors() throws Exception {
        Path trace = root.resolve("trace.jsonl");
        ObjectMapper mapper = new ObjectMapper();
        try (JsonlTraceWriter writer = new JsonlTraceWriter(trace, mapper)) {
            writer.modelResponse(2, new ModelResponse(
                    "{}", 1, 2, 3, 0, "stop", "json_object", 2,
                    "json_schema", "HTTP 400: unavailable"
            ));
            writer.modelError(3, "HTTP 400");
        }

        var lines = Files.readAllLines(trace);
        var response = mapper.readTree(lines.get(0));
        var error = mapper.readTree(lines.get(1));
        assertEquals(2, response.path("requestAttempts").asInt());
        assertEquals("json_schema", response.path("fallbackFrom").asText());
        assertEquals("HTTP 400: unavailable", response.path("fallbackReason").asText());
        assertEquals("model_error", error.path("type").asText());
        assertEquals("HTTP 400", error.path("error").asText());
    }

    @Test
    void redactsAllTracePayloadsWhileKeepingEveryJsonLineValid() throws Exception {
        Path trace = root.resolve("redacted-trace.jsonl");
        ObjectMapper mapper = new ObjectMapper();
        var arguments = mapper.createObjectNode()
                .put("api_key", "argument-secret")
                .put("authorization", "Bearer auth-secret")
                .put("path", "safe.csv");
        arguments.putObject("nested").put("password", "nested-secret");

        try (JsonlTraceWriter writer = new JsonlTraceWriter(trace, mapper)) {
            writer.modelResponse(1, new ModelResponse(
                    "DEEPSEEK_API_KEY=model-secret", 1, 2, 3, 4,
                    "stop", "text", 1, "", ""
            ));
            writer.toolResult(2, "run_command", arguments,
                    ToolResult.success("access_token=tool-secret"));
            writer.protocolError(3, "password: protocol-secret");
            writer.modelError(4, "Bearer model-error-secret");
            writer.completed(5, "checkpoint={\"token\":\"checkpoint-secret\"}");
        }

        var lines = Files.readAllLines(trace);
        assertEquals(5, lines.size());
        String persisted = String.join("\n", lines);
        for (String secret : new String[] {
                "model-secret", "argument-secret", "auth-secret", "nested-secret",
                "tool-secret", "protocol-secret", "model-error-secret", "checkpoint-secret"
        }) {
            assertFalse(persisted.contains(secret), "trace leaked " + secret);
        }
        assertTrue(persisted.contains("[REDACTED]"));

        for (String line : lines) {
            assertTrue(mapper.readTree(line).isObject(), "each JSONL record must remain valid JSON");
        }
        var tool = mapper.readTree(lines.get(1));
        assertEquals("safe.csv", tool.path("arguments").path("path").asText());
        assertEquals("[REDACTED]", tool.path("arguments").path("api_key").asText());
        assertEquals("[REDACTED]", tool.path("arguments").path("nested")
                .path("password").asText());
        assertEquals("argument-secret", arguments.path("api_key").asText(),
                "redaction must not mutate live tool arguments");
    }

    @Test
    void recordsWhatTheHarnessToldTheModel(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        // Traces held the model's words and the tools' output but never the harness's own, so
        // there was no way to tell from a trace whether a mechanism had fired.
        java.nio.file.Path path = dir.resolve("trace.jsonl");
        try (JsonlTraceWriter writer = new JsonlTraceWriter(path, new ObjectMapper())) {
            writer.harnessNote(7, "guidance", "Note: you have reported the same finding 3 turns"
                    + " running.");
        }

        var record = new ObjectMapper().readTree(java.nio.file.Files.readString(path).strip());

        assertEquals("harness_note", record.path("type").asText());
        assertEquals(7, record.path("turn").asInt());
        assertEquals("guidance", record.path("kind").asText());
        assertTrue(record.path("note").asText().contains("same finding 3 turns"));
    }

    @Test
    void prunesTraceEventsOutsideTheRetentionWindow() throws Exception {
        // The file itself never ages - every session appends to it - so the age has to be read
        // off the events. A line without a readable timestamp is kept: unknown is not old.
        Path trace = root.resolve("trace.jsonl");
        java.nio.file.Files.write(trace, java.util.List.of(
                "{\"timestamp\":\"2026-01-01T00:00:00Z\",\"type\":\"model_response\"}",
                "{\"timestamp\":\"" + java.time.Instant.now() + "\",\"type\":\"tool_result\"}",
                "not json at all"
        ));

        int dropped = JsonlTraceWriter.pruneOlderThan(
                trace, java.time.Duration.ofDays(30), new ObjectMapper());

        java.util.List<String> kept = java.nio.file.Files.readAllLines(trace);
        assertEquals(1, dropped);
        assertEquals(2, kept.size());
        assertTrue(kept.get(0).contains("tool_result"), kept.toString());
        assertEquals("not json at all", kept.get(1));
    }

    @Test
    void recordsHowLongTheCallReallyTook() throws Exception {
        // Providers report generation time only. One turn reported 79 seconds against 766
        // seconds of real waiting, so without this the trace cannot account for a run's clock.
        Path trace = root.resolve("wall-clock.jsonl");
        ObjectMapper mapper = new ObjectMapper();
        try (JsonlTraceWriter writer = new JsonlTraceWriter(trace, mapper)) {
            writer.modelResponse(3, new ModelResponse(
                    "{}", 100, 20, 79_000_000_000L, 0, "stop", "json_schema", 1, "", "",
                    766_000_000_000L
            ));
        }

        var event = mapper.readTree(Files.readAllLines(trace).getFirst());
        assertEquals(79_000_000_000L, event.path("durationNanos").asLong());
        assertEquals(766_000_000_000L, event.path("wallClockNanos").asLong());
    }
}
