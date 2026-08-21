package dev.pironi.trace;

import com.fasterxml.jackson.databind.JsonNode;
import dev.pironi.model.ModelResponse;
import dev.pironi.tool.ToolResult;

public interface TraceWriter extends AutoCloseable {
    void modelResponse(int turn, ModelResponse response);

    void protocolError(int turn, String error);

    default void modelError(int turn, String error) {}

    /** A protocol anomaly the run survived. */
    default void protocolWarning(int turn, String warning) {}

    /** What the harness told the model: ledgers, repair instructions, budget warnings. */
    default void harnessNote(int turn, String kind, String note) {}

    void toolResult(int turn, String toolName, JsonNode arguments, ToolResult result);

    void completed(int turn, String finalAnswer);

    /** Which skill was applied for this task and why. */
    default void skillDecision(String chosen, String reason, java.util.List<String> scores) {}

    @Override
    default void close() {
    }
}
