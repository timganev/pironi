package dev.pironi.agent;

import com.fasterxml.jackson.databind.JsonNode;

public record ToolCall(String name, JsonNode arguments) {
    public ToolCall {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("tool name must not be blank");
        }
    }
}
