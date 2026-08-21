package dev.pironi.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.pironi.session.ContextCompressor;
import dev.pironi.session.PersistentAgentMemory;
import dev.pironi.session.SessionStore;
import dev.pironi.session.SkillStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SaveSkillToolTest {
    @TempDir Path temporaryDirectory;

    @Test void savesTheSkillOutright() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        SkillStore skills = new SkillStore(temporaryDirectory);
        PersistentAgentMemory memory = new PersistentAgentMemory(
                new SessionStore(temporaryDirectory, mapper),
                new ContextCompressor(10_000, mapper), skills, mapper,
                "model", temporaryDirectory, 10_000, 8
        );
        SaveSkillTool tool = new SaveSkillTool(memory);
        var arguments = mapper.createObjectNode()
                .put("name", "weekly-status")
                .put("description", "Prepare weekly status");
        arguments.putArray("steps").add("Collect owners").add("Verify blockers");
        arguments.putArray("triggers").add("weekly status");

        ToolResult result = tool.execute(arguments);

        // Asking for a skill and getting a draft that dies with the session is not a skill.
        assertTrue(result.success(), result.output());
        assertTrue(result.output().contains("Skill saved: weekly-status"), result.output());
        assertTrue(skills.load("weekly-status").orElse("").contains("Collect owners"));
    }

    @Test void savingAgainReplacesAndArchives() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        SkillStore skills = new SkillStore(temporaryDirectory);
        PersistentAgentMemory memory = new PersistentAgentMemory(
                new SessionStore(temporaryDirectory, mapper),
                new ContextCompressor(10_000, mapper), skills, mapper,
                "model", temporaryDirectory, 10_000, 8
        );
        SaveSkillTool tool = new SaveSkillTool(memory);
        var first = mapper.createObjectNode().put("name", "flow").put("description", "v1");
        first.putArray("steps").add("original step");
        assertTrue(tool.execute(first).success());

        var second = mapper.createObjectNode().put("name", "flow").put("description", "v2");
        second.putArray("steps").add("corrected step");
        ToolResult result = tool.execute(second);

        // "change it" is one turn, not a review ceremony.
        assertTrue(result.output().contains("Skill updated"), result.output());
        assertTrue(skills.load("flow").orElse("").contains("corrected step"));
    }

    @Test void rejectsMissingOrOversizedArrays() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        PersistentAgentMemory memory = new PersistentAgentMemory(
                new SessionStore(temporaryDirectory, mapper),
                new ContextCompressor(10_000, mapper), new SkillStore(temporaryDirectory), mapper,
                "model", temporaryDirectory, 10_000, 8
        );
        SaveSkillTool tool = new SaveSkillTool(memory);
        var arguments = mapper.createObjectNode()
                .put("name", "x").put("description", "x");

        assertFalse(tool.execute(arguments).success());
        var steps = arguments.putArray("steps");
        for (int index = 0; index < 13; index++) steps.add("step " + index);
        assertFalse(tool.execute(arguments).success());
    }
}
