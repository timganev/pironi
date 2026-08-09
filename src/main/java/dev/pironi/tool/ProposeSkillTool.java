package dev.pironi.tool;

import com.fasterxml.jackson.databind.JsonNode;
import dev.pironi.session.PersistentAgentMemory;

import java.util.ArrayList;
import java.util.List;

/** Creates an ephemeral learning draft; only a later slash command can persist it. */
public final class ProposeSkillTool implements Tool {
    private final PersistentAgentMemory memory;

    public ProposeSkillTool(PersistentAgentMemory memory) {
        this.memory = memory;
    }

    @Override public String name() { return "propose_skill"; }

    @Override public String description() {
        return "Prepare a non-persistent reusable workflow draft after an explicit first-party "
                + "user correction. It never writes a skill; the user must review and accept it.";
    }

    @Override public String argumentSchema() {
        return "{\"name\":\"ASCII slug, required\",\"description\":\"string, required\","
                + "\"steps\":\"array of 1..12 strings, required\","
                + "\"triggers\":\"array of up to 8 strings, optional\","
                + "\"exclusions\":\"array of up to 8 strings, optional\","
                + "\"evidence\":\"short string explaining the explicit user correction, required\"}";
    }

    @Override public boolean mutating() { return false; }

    @Override public ToolResult validate(JsonNode arguments) {
        try {
            ToolArguments.requiredText(arguments, "name");
            ToolArguments.requiredText(arguments, "description");
            ToolArguments.requiredText(arguments, "evidence");
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
        return ToolResult.success(memory.proposeSkill(
                arguments.path("name").asText(),
                arguments.path("description").asText(),
                strings(arguments, "steps", true, 12),
                strings(arguments, "triggers", false, 8),
                strings(arguments, "exclusions", false, 8),
                arguments.path("evidence").asText()
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
