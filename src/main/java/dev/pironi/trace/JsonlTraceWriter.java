package dev.pironi.trace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.pironi.model.ModelResponse;
import dev.pironi.session.SecretRedactor;
import dev.pironi.tool.ToolResult;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

public final class JsonlTraceWriter implements TraceWriter {
    private final ObjectMapper objectMapper;
    private final BufferedWriter writer;

    /**
     * Drops events older than the retention window before the trace is opened. The file only
     * grows and holds what the agent read, and its own age never ages - every session touches it
     * again - so the age is read off the events inside.
     *
     * @return how many events were dropped
     */
    public static int pruneOlderThan(Path path, java.time.Duration retention,
            ObjectMapper objectMapper) {
        if (!Files.isRegularFile(path)) return 0;
        Instant cutoff = Instant.now().minus(retention);
        try {
            java.util.List<String> kept = new java.util.ArrayList<>();
            int dropped = 0;
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                if (recordedBefore(line, cutoff, objectMapper)) dropped++;
                else kept.add(line);
            }
            if (dropped > 0) Files.write(path, kept, StandardCharsets.UTF_8);
            return dropped;
        } catch (IOException | UncheckedIOException e) {
            // A trace that cannot be pruned is not worth failing a run over.
            return 0;
        }
    }

    /** A line without a readable timestamp is kept: age unknown is not the same as old. */
    private static boolean recordedBefore(String line, Instant cutoff, ObjectMapper objectMapper) {
        try {
            JsonNode node = objectMapper.readTree(line);
            String timestamp = node.path("timestamp").asText("");
            return !timestamp.isEmpty() && Instant.parse(timestamp).isBefore(cutoff);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            return false;
        }
    }

    public JsonlTraceWriter(Path path, ObjectMapper objectMapper) throws IOException {
        Path parent = path.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        this.objectMapper = objectMapper;
        this.writer = Files.newBufferedWriter(
                path,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
        );
    }

    @Override
    public synchronized void modelResponse(int turn, ModelResponse response) {
        ObjectNode event = event("model_response", turn);
        event.put("content", response.content());
        event.put("promptTokens", response.promptTokens());
        event.put("outputTokens", response.outputTokens());
        event.put("durationNanos", response.durationNanos());
        event.put("evalDurationNanos", response.evalDurationNanos());
        if (response.wallClockNanos() > 0) event.put("wallClockNanos", response.wallClockNanos());
        event.put("finishReason", response.finishReason());
        event.put("responseFormat", response.responseFormat());
        event.put("requestAttempts", response.requestAttempts());
        if (!response.fallbackFrom().isBlank()) event.put("fallbackFrom", response.fallbackFrom());
        if (!response.fallbackReason().isBlank()) event.put("fallbackReason", response.fallbackReason());
        write(event);
    }

    @Override
    public synchronized void protocolError(int turn, String error) {
        write(event("protocol_error", turn).put("error", error));
    }

    @Override
    public synchronized void modelError(int turn, String error) {
        write(event("model_error", turn).put("error", error));
    }

    @Override
    public synchronized void protocolWarning(int turn, String warning) {
        write(event("protocol_warning", turn).put("warning", warning));
    }

    @Override
    public synchronized void harnessNote(int turn, String kind, String note) {
        write(event("harness_note", turn).put("kind", kind).put("note", note));
    }

    @Override
    public synchronized void toolResult(
            int turn,
            String toolName,
            JsonNode arguments,
            ToolResult result
    ) {
        ObjectNode event = event("tool_result", turn);
        event.put("tool", toolName);
        event.set("arguments", arguments);
        event.put("success", result.success());
        event.put("output", result.output());
        write(event);
    }

    @Override
    public synchronized void completed(int turn, String finalAnswer) {
        write(event("completed", turn).put("finalAnswer", finalAnswer));
    }

    @Override
    public void skillDecision(String chosen, String reason, java.util.List<String> scores) {
        ObjectNode event = event("skill_decision", 0)
                .put("chosen", chosen == null ? "" : chosen)
                .put("reason", reason == null ? "" : reason);
        event.set("scores", objectMapper.valueToTree(scores == null ? java.util.List.of() : scores));
        write(event);
    }

    @Override
    public synchronized void close() {
        try {
            writer.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private ObjectNode event(String type, int turn) {
        return objectMapper.createObjectNode()
                .put("timestamp", Instant.now().toString())
                .put("type", type)
                .put("turn", turn);
    }

    private void write(ObjectNode event) {
        try {
            writer.write(objectMapper.writeValueAsString(SecretRedactor.redact(event)));
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
