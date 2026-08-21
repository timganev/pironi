package dev.pironi.tool;

import com.fasterxml.jackson.databind.JsonNode;
import dev.pironi.session.PersistentAgentMemory;

import java.util.ArrayList;
import java.util.List;

/**
 * Writes a reusable skill to the skill store. Asking for one used to produce a draft that a slash
 * command had to accept, and the ceremony lost more than it protected: the draft died with the
 * session, the accept command refused the name the agent told the user to type, and a skill that
 * was asked for, written and approved was simply gone. Saying "change it" costs one turn.
 */
public final class SaveSkillTool implements Tool {
    private final PersistentAgentMemory memory;

    public SaveSkillTool(PersistentAgentMemory memory) {
        this.memory = memory;
    }

    @Override public String name() { return "save_skill"; }

    @Override public String description() {
        return "Save a reusable workflow the user asked for, so later sessions can apply it. "
                + "An existing skill of the same name is replaced and its previous version "
                + "archived.";
    }

    @Override public String argumentSchema() {
        return "{\"name\":\"ASCII slug, required\",\"description\":\"string, required\","
                + "\"steps\":\"array of 1..12 strings, required\","
                + "\"triggers\":\"array of up to 8 strings, optional\","
                + "\"exclusions\":\"array of up to 8 strings, optional: single words that must "
                + "keep this skill from being chosen when they appear in the request. Each word "
                + "acts on its own, so never include one that belongs to the skill's own "
                + "subject, and never write instructions about how to behave here\","
                + "\"evidence\":\"short string saying why, optional\"}";
    }

    /** It writes to the skill store, so an approval policy that asks about mutations asks here. */
    @Override public boolean mutating() { return true; }

    @Override public ToolResult validate(JsonNode arguments) {
        try {
            ToolArguments.requiredText(arguments, "name");
            ToolArguments.requiredText(arguments, "description");
            strings(arguments, "steps", true, 12);
            strings(arguments, "triggers", false, 8);
            strings(arguments, "exclusions", false, 8);
            return ToolResult.success("validated");
        } catch (IllegalArgumentException e) {
            return ToolResult.failure(e.getMessage());
        }
    }

    @Override public ToolResult execute(JsonNode arguments) {
        ToolResult validation = validate(arguments);
        if (!validation.success()) return validation;
        return ToolResult.success(memory.saveSkill(
                arguments.path("name").asText(),
                arguments.path("description").asText(),
                strings(arguments, "steps", true, 12),
                strings(arguments, "triggers", false, 8),
                strings(arguments, "exclusions", false, 8),
                arguments.path("evidence").asText("requested by the user")
        ));
    }

    private static List<String> strings(
            JsonNode arguments, String field, boolean required, int maxItems
    ) {
        JsonNode node = arguments.get(field);
        if (node == null || node.isNull()) {
            if (required) throw new IllegalArgumentException(field + " is required");
            return List.of();
        }
        if (!node.isArray() || node.size() > maxItems || (required && node.isEmpty())) {
            throw new IllegalArgumentException(
                    field + " must be an array of " + (required ? "1.." : "0..") + maxItems
                            + " strings"
            );
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isTextual() || item.textValue().isBlank()) {
                throw new IllegalArgumentException(field + " entries must be non-blank strings");
            }
            values.add(item.textValue());
        }
        return List.copyOf(values);
    }
}
