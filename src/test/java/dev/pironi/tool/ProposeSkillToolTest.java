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

class ProposeSkillToolTest {
    @TempDir Path temporaryDirectory;

    @Test void createsDraftWithoutDurableWrite() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        SkillStore skills = new SkillStore(temporaryDirectory);
        PersistentAgentMemory memory = new PersistentAgentMemory(
                new SessionStore(temporaryDirectory, mapper),
                new ContextCompressor(10_000, mapper), skills, mapper,
                "model", temporaryDirectory, 10_000, 8
        );
        ProposeSkillTool tool = new ProposeSkillTool(memory);
        var arguments = mapper.createObjectNode()
                .put("name", "weekly-status")
                .put("description", "Prepare weekly status");
        arguments.putArray("steps").add("Collect owners").add("Verify blockers");
        arguments.putArray("triggers").add("weekly status");
        arguments.put("evidence", "The user explicitly corrected the process");

        ToolResult result = tool.execute(arguments);

        assertTrue(result.success(), result.output());
        assertTrue(result.output().contains("not saved"));
        assertTrue(skills.load("weekly-status").isEmpty());
        assertTrue(memory.pendingSkill().contains("Collect owners"));
    }

    @Test void rejectsMissingOrOversizedArrays() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        PersistentAgentMemory memory = new PersistentAgentMemory(
                new SessionStore(temporaryDirectory, mapper),
                new ContextCompressor(10_000, mapper), new SkillStore(temporaryDirectory), mapper,
                "model", temporaryDirectory, 10_000, 8
        );
        ProposeSkillTool tool = new ProposeSkillTool(memory);
        var arguments = mapper.createObjectNode()
                .put("name", "x").put("description", "x").put("evidence", "x");

        assertFalse(tool.execute(arguments).success());
        var steps = arguments.putArray("steps");
        for (int index = 0; index < 13; index++) steps.add("step " + index);
        assertFalse(tool.execute(arguments).success());
    }
}
