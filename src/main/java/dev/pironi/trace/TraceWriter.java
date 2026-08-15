package dev.pironi.trace;

import com.fasterxml.jackson.databind.JsonNode;
import dev.pironi.model.ModelResponse;
import dev.pironi.tool.ToolResult;

public interface TraceWriter extends AutoCloseable {
    void modelResponse(int turn, ModelResponse response);

    void protocolError(int turn, String error);

    default void modelError(int turn, String error) {}

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
