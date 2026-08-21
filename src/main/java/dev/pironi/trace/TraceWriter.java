package dev.pironi.trace;

import com.fasterxml.jackson.databind.JsonNode;
import dev.pironi.model.ModelResponse;
import dev.pironi.tool.ToolResult;

public interface TraceWriter extends AutoCloseable {
    void modelResponse(int turn, ModelResponse response);

    void protocolError(int turn, String error);

    default void modelError(int turn, String error) {}

    /**
     * A protocol anomaly the run survived. Default no-op so existing writers and test doubles are
     * unaffected. Kept apart from {@link #protocolError} so tolerating something does not read as
     * a failed turn when the trace is counted.
     */
    default void protocolWarning(int turn, String warning) {}

    /**
     * What the harness told the model: ledgers, repair instructions, budget warnings. Traces held
     * the model's words and the tools' output but never our own, so nothing said whether a
     * mechanism had fired. No-op by default, so existing writers are unaffected.
     */
    default void harnessNote(int turn, String kind, String note) {}

    void toolResult(int turn, String toolName, JsonNode arguments, ToolResult result);

    void completed(int turn, String finalAnswer);

    /**
     * Which skill was applied for this task and why. Default no-op so existing writers and
     * test doubles are unaffected.
     */
    default void skillDecision(String chosen, String reason, java.util.List<String> scores) {}

    @Override
    default void close() {
    }
}
