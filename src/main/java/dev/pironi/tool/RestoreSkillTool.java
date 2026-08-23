package dev.pironi.tool;

import com.fasterxml.jackson.databind.JsonNode;
import dev.pironi.session.PersistentAgentMemory;

/**
 * Brings a skill back out of the archive. Deleting and pruning both only move the folder, so the
 * copy was always there - with no way to reach it, recovery meant the user moving directories by
 * hand, which is not a recovery the agent can offer.
 */
public final class RestoreSkillTool implements Tool {
    private final PersistentAgentMemory memory;

    public RestoreSkillTool(PersistentAgentMemory memory) {
        this.memory = memory;
    }

    @Override public String name() { return "restore_skill"; }

    @Override public String description() {
        return "Restore a skill that was deleted, or archived for going unused, by name. "
                + "Naming one that is not archived lists what is.";
    }

    @Override public String argumentSchema() {
        return "{\"name\":\"ASCII slug of the skill to restore, required\"}";
    }

    @Override public boolean mutating() { return true; }

    @Override public ToolResult validate(JsonNode arguments) {
        try {
            ToolArguments.requiredText(arguments, "name");
            return ToolResult.success("validated");
        } catch (IllegalArgumentException e) {
            return ToolResult.failure(e);
        }
    }

    @Override public ToolResult execute(JsonNode arguments) {
        ToolResult validation = validate(arguments);
        if (!validation.success()) return validation;
        String result = memory.restoreSkill(arguments.path("name").asText());
        return result.startsWith("Skill restored:")
                ? ToolResult.success(result)
                : ToolResult.failure(result);
    }
}
