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

    /** Actions that must never be auto-approved, even under approval=auto. */
    /**
     * Whether this particular call changes anything. run_command is mutating as a tool and yet
     * most of its calls only read, and asking about those trains the user to approve without
     * reading. Tools that are mutating whatever the arguments say need not override this.
     */
    default boolean mutating(JsonNode arguments) {
        return mutating();
    }

    default boolean requiresExplicitApproval(JsonNode arguments) {
        return false;
    }

    default String approvalPreview(JsonNode arguments) {
        return arguments.toString();
    }

    /** Validate arguments and policy-sensitive paths without changing external state. */
    default ToolResult validate(JsonNode arguments) {
        return ToolResult.success("validated");
    }

    ToolResult execute(JsonNode arguments);
}
