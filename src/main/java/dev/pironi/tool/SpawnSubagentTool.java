package dev.pironi.tool;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Hands a sub-task to a child so the user does not wait idle through a multi-step operation.
 * Cloud providers only. The call returns a handle at once; the child runs on a virtual thread and
 * its result is drained into the main loop next turn.
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
        return "ALWAYS use this when you need multiple http_get calls (e.g. fetching several URLs "
                + "or querying the same API with different parameters). It spawns a background child "
                + "agent that runs the fetches in parallel and delivers the collected results before "
                + "your next turn. The child is read-only (http_get, read_file, list_files, "
                + "find_files). NEVER perform the same http_get calls yourself after spawning — the "
                + "child does the work and its result will be injected into the conversation. "
                + "Effective for: weather, prices, multi-city lookups, multi-URL scraping.";
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
                    : ToolResult.success(
                            "Агент «" + name + "» е стартиран във фонов режим; "
                                    + "резултатът ще пристигне по-късно, продължи разговора.");
        } catch (IllegalArgumentException e) {
            return ToolResult.failure(e.getMessage());
        }
    }
}
