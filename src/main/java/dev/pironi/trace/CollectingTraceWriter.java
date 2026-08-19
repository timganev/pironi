package dev.pironi.trace;

import com.fasterxml.jackson.databind.JsonNode;
import dev.pironi.model.ModelResponse;
import dev.pironi.tool.ToolResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Bounded, in-memory {@link TraceWriter} used by sub-agent runs so the parent can see what
 * the child actually did (e.g. which tools it called and whether they succeeded). Only
 * {@code toolResult}, {@code protocolError} and {@code completed} are recorded — model
 * responses are large and not needed for attribution.
 */
public final class CollectingTraceWriter implements TraceWriter {
    private static final int MAX_LINES = 50;
    private static final int MAX_LINE_CHARS = 200;

    private final List<String> lines = new ArrayList<>();

    @Override public void modelResponse(int turn, ModelResponse response) {
        // intentionally not recorded
    }

    @Override
    public void protocolError(int turn, String error) {
        add("t" + turn + " protocol_error " + truncate(error));
    }

    @Override
    public void modelError(int turn, String error) {
        add("t" + turn + " model_error " + truncate(error));
    }

    @Override
    public void toolResult(int turn, String toolName, JsonNode arguments, ToolResult result) {
        add("t" + turn + " " + toolName + " "
                + (result.success() ? "ok" : "fail")
                + " " + truncate(arguments == null ? "" : arguments.toString())
                + " -> " + truncate(result.output()));
    }

    @Override
    public void completed(int turn, String finalAnswer) {
        add("t" + turn + " completed " + truncate(finalAnswer));
    }

    @Override
    public void harnessNote(int turn, String kind, String note) {
        add("t" + turn + " harness_note " + kind + " " + truncate(note));
    }

    /** Returns an immutable snapshot of the collected lines, oldest first. */
    public synchronized List<String> lines() {
        return List.copyOf(lines);
    }

    private synchronized void add(String line) {
        if (lines.size() >= MAX_LINES) {
            lines.remove(0);
        }
        lines.add(line.length() <= MAX_LINE_CHARS ? line : line.substring(0, MAX_LINE_CHARS));
    }

    private static String truncate(String s) {
        if (s == null) return "";
        String single = s.replaceAll("[\\r\\n]+", " ").trim();
        return single.length() <= MAX_LINE_CHARS ? single : single.substring(0, MAX_LINE_CHARS);
    }
}
