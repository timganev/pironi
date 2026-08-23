package dev.pironi.tool;

import com.fasterxml.jackson.databind.JsonNode;
import dev.pironi.session.SkillStore;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Reads one skill by name.
 *
 * <p>Exactly one skill is chosen for a task, and until now that was the only one there would ever
 * be: the store could be written to and listed, but never read from. So a skill that points at
 * another - "what carries the grouping is in email-triage" - was pointing at a door with no
 * handle.
 *
 * <p>It cost a real answer. Asked to trace a week of one client's correspondence, the agent got
 * the retrieval skill, searched subject lines for the client's name, found two threads of five and
 * reported that nothing happened after Tuesday - while the skill holding "follow the story through
 * its people, not through a keyword" sat unread in the same store.
 */
public final class ReadSkillTool implements Tool {
    private final SkillStore skills;

    public ReadSkillTool(SkillStore skills) {
        this.skills = skills;
    }

    @Override
    public String name() {
        return "read_skill";
    }

    @Override
    public String description() {
        return "Read an installed skill by name. One skill is applied automatically; use this to "
                + "follow a reference from it to another, or when the task turns out to be about "
                + "something a different skill covers. Call with no name to list what is there.";
    }

    @Override
    public String argumentSchema() {
        return "{\"name\":\"skill name, optional; omit to list the installed skills\"}";
    }

    @Override
    public boolean mutating() {
        return false;
    }

    @Override
    public ToolResult execute(JsonNode arguments) {
        JsonNode supplied = arguments == null ? null : arguments.get("name");
        String name = supplied != null && supplied.isTextual() ? supplied.textValue().strip() : "";
        try {
            if (name.isEmpty()) return ToolResult.success(catalogue());
            return skills.load(name)
                    .map(ToolResult::success)
                    // Naming the alternatives beats "not found", which reads as "there are none".
                    .orElseGet(() -> ToolResult.failure(
                            "No skill called '" + name + "'. Installed: " + names()));
        } catch (IOException e) {
            return ToolResult.failure(e);
        }
    }

    private String catalogue() throws IOException {
        List<SkillStore.SkillEntry> installed = skills.list();
        if (installed.isEmpty()) return "No skills installed.";
        return installed.stream()
                .map(entry -> "- " + entry.name() + ": " + entry.description())
                .collect(Collectors.joining("\n"));
    }

    private String names() {
        try {
            List<String> installed = skills.list().stream()
                    .map(SkillStore.SkillEntry::name)
                    .toList();
            return installed.isEmpty() ? "none" : String.join(", ", installed);
        } catch (IOException e) {
            return "unreadable";
        }
    }
}
