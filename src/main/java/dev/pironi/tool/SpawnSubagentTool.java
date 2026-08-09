package dev.pironi.tool;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Lets the main agent hand a sub-task to a sub-agent so the user does not wait idle
 * on a multi-step operation. Registered only for cloud providers (never Ollama).
 * The call returns immediately with a handle; the child runs in a virtual thread and
 * its result is drained into the main loop at the start of the next turn.
 */
public final class SpawnSubagentTool implements Tool {
    static final int MAX_TASK_CHARS = 2_000;
    static final int MAX_NAME_CHARS = 64;
    private final SubagentManager manager;

    public SpawnSubagentTool(SubagentManager manager) {
        this.manager = manager;
    }

    @Override public String name() { return "spawn_subagent"; }

    @Override public String description() {
        return "Offload a data-gathering sub-task to a sub-agent running the same cloud model in "
                + "a virtual thread, so this main agent stays responsive and the user can keep typing. "
                + "Use only for multi-step or research-heavy work that would otherwise block a reply for a "
                + "while (several tool calls, several HTTP fetches). The child is read-only: it can only run "
                + "http_get, read_file, list_files and find_files. Returns immediately with a handle; Pironi "
                + "waits for the child before your next turn, so never repeat the delegated work yourself.";
    }

    @Override public String argumentSchema() {
        return "{\"name\":\"short label for the sub-agent, required\","
                + "\"task\":\"description of the sub-task for the sub-agent, required, <=2000 chars\"}";
    }

    @Override public boolean mutating() { return false; }

    @Override public boolean requiresExplicitApproval(JsonNode arguments) { return false; }

    @Override public ToolResult validate(JsonNode arguments) {
        String name = ToolArguments.requiredText(arguments, "name");
        String task = ToolArguments.requiredText(arguments, "task");
        if (name.length() > MAX_NAME_CHARS) {
            return ToolResult.failure("name exceeds " + MAX_NAME_CHARS + " chars");
        }
        if (task.length() > MAX_TASK_CHARS) {
            return ToolResult.failure("task exceeds " + MAX_TASK_CHARS + " chars");
        }
        return ToolResult.success("validated");
    }

    @Override public ToolResult execute(JsonNode arguments) {
        try {
            String name = ToolArguments.requiredText(arguments, "name");
            String task = ToolArguments.requiredText(arguments, "task");
            if (task.length() > MAX_TASK_CHARS) {
                return ToolResult.failure("task exceeds " + MAX_TASK_CHARS + " chars");
            }
            SubagentResult spawned = manager.spawn(name, task);
            return spawned.status().equals("error")
                    ? ToolResult.failure(spawned.output())
                    : ToolResult.success(spawned.output());
        } catch (IllegalArgumentException e) {
            return ToolResult.failure(e.getMessage());
        }
    }
}
