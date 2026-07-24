package dev.pironi.trace;

import com.fasterxml.jackson.databind.JsonNode;
import dev.pironi.model.ModelResponse;
import dev.pironi.tool.ToolResult;

public final class NoOpTraceWriter implements TraceWriter {
    @Override
    public void modelResponse(int turn, ModelResponse response) {
    }

    @Override
    public void protocolError(int turn, String error) {
    }

    @Override
    public void toolResult(int turn, String toolName, JsonNode arguments, ToolResult result) {
    }

    @Override
    public void completed(int turn, String finalAnswer) {
    }
}
