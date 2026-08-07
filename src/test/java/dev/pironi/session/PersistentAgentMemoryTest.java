package dev.pironi.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.pironi.model.ChatMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PersistentAgentMemoryTest {
    @TempDir Path temporaryDirectory;

    @Test void checkpointsAndResumesStructuredMessages() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        SessionStore sessions = new SessionStore(temporaryDirectory, mapper);
        SkillStore skills = new SkillStore(temporaryDirectory);
        PersistentAgentMemory first = memory(sessions, skills, mapper);
        first.begin("goal");
        first.checkpoint(List.of(
                ChatMessage.system("system"), ChatMessage.user("goal"),
                ChatMessage.assistant("answer")
        ), "goal");
        String id = sessions.currentMeta().id();

        PersistentAgentMemory second = memory(
                new SessionStore(temporaryDirectory, mapper), skills, mapper
        );
        assertTrue(second.resume(id).contains("3 messages"));
        List<ChatMessage> restored = second.begin("continue");
        assertEquals(3, restored.size());
        assertEquals("answer", restored.getLast().content());
    }

    @Test void activatesAndSavesSkillsFromLastCompletedTurn() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        SkillStore skills = new SkillStore(temporaryDirectory);
        assertTrue(skills.save("review", "---\ndescription: Review\n---\nCheck carefully"));
        PersistentAgentMemory memory = memory(
                new SessionStore(temporaryDirectory, mapper), skills, mapper
        );

        assertTrue(memory.activateSkill("review").contains("activated"));
        assertTrue(memory.promptContext().contains("Check carefully"));
        memory.completed("fix bug", "tests pass");
        assertEquals("Skill saved: lesson", memory.saveLastTurnAsSkill("lesson"));
        assertTrue(skills.load("lesson").orElseThrow().contains("fix bug"));
        assertEquals("Active skill cleared.", memory.activateSkill("off"));
    }

    private PersistentAgentMemory memory(
            SessionStore sessions, SkillStore skills, ObjectMapper mapper
    ) {
        return new PersistentAgentMemory(
                sessions, new ContextCompressor(10_000, mapper), skills, mapper,
                "model", Path.of("/workspace/project"), 10_000, 8
        );
    }
}
