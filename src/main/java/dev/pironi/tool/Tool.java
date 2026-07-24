package dev.pironi.tool;

import com.fasterxml.jackson.databind.JsonNode;

public interface Tool {
    String name();

    String description();

    String argumentSchema();

    boolean mutating();

    default boolean requiresVerification() {
        return mutating();
    }

    default String approvalPreview(JsonNode arguments) {
        return arguments.toString();
    }

    ToolResult execute(JsonNode arguments);
}
