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
     * Whether this particular call changes anything. run_command is mutating as a tool and yet most
     * of its calls only read, and asking about those trains the user to approve without reading.
     */
    default boolean mutating(JsonNode arguments) {
        return mutating();
    }

    default boolean requiresExplicitApproval(JsonNode arguments) {
        return false;
    }

    /**
     * Whether this alters the rules the rest of the session runs under, rather than acting within
     * them. approval=auto means "act without asking"; it does not mean "and move the boundary of
     * what acting may touch", so these are confirmed in every mode.
     */
    default boolean changesTheRules(JsonNode arguments) {
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
