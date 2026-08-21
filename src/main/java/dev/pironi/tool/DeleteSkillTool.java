package dev.pironi.tool;

import com.fasterxml.jackson.databind.JsonNode;
import dev.pironi.session.PersistentAgentMemory;

/**
 * Removes a skill from the store. save_skill shipped without a counterpart, so an agent asked to
 * delete one answered that it had no way to - while holding write access to the very folder it
 * had just written into. Offering to overwrite the skill with an empty one instead was the only
 * thing left, and that is not deletion.
 */
public final class DeleteSkillTool implements Tool {
    private final PersistentAgentMemory memory;

    public DeleteSkillTool(PersistentAgentMemory memory) {
        this.memory = memory;
    }

    @Override public String name() { return "delete_skill"; }

    @Override public String description() {
        return "Delete a stored skill the user no longer wants, by name. The folder is kept "
                + "under .archive, so a name deleted by mistake can be restored.";
    }

    @Override public String argumentSchema() {
        return "{\"name\":\"ASCII slug of the skill to delete, required\"}";
    }

    @Override public boolean mutating() { return true; }

    @Override public ToolResult validate(JsonNode arguments) {
        try {
            ToolArguments.requiredText(arguments, "name");
            return ToolResult.success("validated");
        } catch (IllegalArgumentException e) {
            return ToolResult.failure(e.getMessage());
        }
    }

    @Override public ToolResult execute(JsonNode arguments) {
        ToolResult validation = validate(arguments);
        if (!validation.success()) return validation;
        String result = memory.deleteSkill(arguments.path("name").asText());
        // Naming a skill that is not there is a wrong argument, not a completed deletion.
        return result.startsWith("Skill deleted:")
                ? ToolResult.success(result)
                : ToolResult.failure(result);
    }
}
