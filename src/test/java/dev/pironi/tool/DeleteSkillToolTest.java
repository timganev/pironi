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

class DeleteSkillToolTest {
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

    @Test void deletesTheNamedSkill() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        SkillStore skills = new SkillStore(temporaryDirectory);
        PersistentAgentMemory memory = memoryOver(skills, mapper);
        saveTop5(memory);

        ToolResult result = new DeleteSkillTool(memory)
                .execute(mapper.createObjectNode().put("name", "top5"));

        // save_skill without a counterpart left the agent explaining it could not delete.
        assertTrue(result.success(), result.output());
        assertFalse(skills.exists("top5"));
        assertTrue(skills.listArchived().contains("top5"));
    }

    @Test void deletionSurvivesAsARestorableCopy() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        SkillStore skills = new SkillStore(temporaryDirectory);
        PersistentAgentMemory memory = memoryOver(skills, mapper);
        saveTop5(memory);
        new DeleteSkillTool(memory).execute(mapper.createObjectNode().put("name", "top5"));

        assertTrue(skills.restore("top5"));
        assertTrue(skills.load("top5").orElse("").contains("forecast endpoint"));
    }

    @Test void anUnknownNameFailsAndNamesWhatIsStored() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        SkillStore skills = new SkillStore(temporaryDirectory);
        PersistentAgentMemory memory = memoryOver(skills, mapper);
        saveTop5(memory);

        ToolResult result = new DeleteSkillTool(memory)
                .execute(mapper.createObjectNode().put("name", "top50"));

        // Reporting a deletion that did not happen is worse than refusing to delete at all.
        assertFalse(result.success(), result.output());
        assertTrue(result.output().contains("top5"), result.output());
        assertTrue(skills.exists("top5"));
    }

    @Test void deletingTheActiveSkillStopsItSteeringThisTurn() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        SkillStore skills = new SkillStore(temporaryDirectory);
        PersistentAgentMemory memory = memoryOver(skills, mapper);
        saveTop5(memory);
        memory.activateSkill("top5");
        assertEquals("top5", memory.activeSkillName());

        new DeleteSkillTool(memory).execute(mapper.createObjectNode().put("name", "top5"));

        assertEquals("", memory.activeSkillName());
    }

    @Test void itIsMutatingSoAnApprovalPolicyCanSeeIt() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        PersistentAgentMemory memory = memoryOver(new SkillStore(temporaryDirectory), mapper);
        assertTrue(new DeleteSkillTool(memory).mutating());
    }
}
