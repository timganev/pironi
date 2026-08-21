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

class RestoreSkillToolTest {
    @TempDir Path temporaryDirectory;

    private PersistentAgentMemory memoryOver(SkillStore skills, ObjectMapper mapper)
            throws Exception {
        return new PersistentAgentMemory(
                new SessionStore(temporaryDirectory, mapper),
                new ContextCompressor(10_000, mapper), skills, mapper,
                "model", temporaryDirectory, 10_000, 8
        );
    }

    private void saveTop5(PersistentAgentMemory memory) {
        memory.saveSkill("top5", "Weather for five cities",
                java.util.List.of("Call the forecast endpoint"),
                java.util.List.of("weather"), java.util.List.of(), "asked for");
    }

    @Test void bringsBackADeletedSkill() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        SkillStore skills = new SkillStore(temporaryDirectory);
        PersistentAgentMemory memory = memoryOver(skills, mapper);
        saveTop5(memory);
        new DeleteSkillTool(memory).execute(mapper.createObjectNode().put("name", "top5"));

        ToolResult result = new RestoreSkillTool(memory)
                .execute(mapper.createObjectNode().put("name", "top5"));

        assertTrue(result.success(), result.output());
        assertTrue(skills.load("top5").orElse("").contains("forecast endpoint"));
    }

    @Test void anUnarchivedNameFailsAndListsWhatIsArchived() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        SkillStore skills = new SkillStore(temporaryDirectory);
        PersistentAgentMemory memory = memoryOver(skills, mapper);
        saveTop5(memory);
        new DeleteSkillTool(memory).execute(mapper.createObjectNode().put("name", "top5"));

        ToolResult result = new RestoreSkillTool(memory)
                .execute(mapper.createObjectNode().put("name", "top9"));

        assertFalse(result.success(), result.output());
        assertTrue(result.output().contains("top5"), result.output());
    }

    @Test void restoringOverALiveSkillIsRefusedRatherThanSilent() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        PersistentAgentMemory memory = memoryOver(new SkillStore(temporaryDirectory), mapper);
        saveTop5(memory);

        ToolResult result = new RestoreSkillTool(memory)
                .execute(mapper.createObjectNode().put("name", "top5"));

        assertFalse(result.success(), result.output());
        assertTrue(result.output().contains("already"), result.output());
    }
}
