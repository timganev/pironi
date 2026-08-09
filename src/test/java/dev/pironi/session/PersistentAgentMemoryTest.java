package dev.pironi.session;

import com.fasterxml.jackson.databind.JsonNode;
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

        SessionStore resumedSessions = new SessionStore(temporaryDirectory, mapper);
        PersistentAgentMemory second = memory(resumedSessions, skills, mapper);
        assertTrue(second.resume(id).contains("3 messages"));
        List<ChatMessage> restored = second.begin("continue");
        assertEquals(3, restored.size());
        assertEquals("answer", restored.getLast().content());
        assertNotEquals(id, resumedSessions.currentMeta().id(),
                "resume must fork into a new session instead of writing into an unrelated one");
        JsonNode resumedCheckpoint = mapper.readTree(
                resumedSessions.loadCheckpoint(resumedSessions.currentMeta().id()).orElseThrow());
        assertEquals(id, resumedCheckpoint.path("resumedFrom").asText());
    }

    @Test void resumeRestoresSummaryAndClearsSkillMissingFromCheckpoint() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        SessionStore sessions = new SessionStore(temporaryDirectory, mapper);
        SkillStore skills = new SkillStore(temporaryDirectory);
        assertTrue(skills.save("review", "---\ndescription: Review\n---\nCheck carefully"));
        ContextCompressor firstCompressor = new ContextCompressor(10_000, mapper);
        PersistentAgentMemory first = new PersistentAgentMemory(
                sessions, firstCompressor, skills, mapper, "model",
                Path.of("/workspace/project"), 10_000, 8
        );
        first.begin("goal");
        firstCompressor.storeSummary("bounded working summary");
        first.checkpoint(List.of(ChatMessage.system("system"), ChatMessage.user("goal")), "goal");
        String idWithoutSkill = sessions.currentMeta().id();

        ContextCompressor secondCompressor = new ContextCompressor(10_000, mapper);
        PersistentAgentMemory second = new PersistentAgentMemory(
                new SessionStore(temporaryDirectory, mapper), secondCompressor, skills, mapper,
                "model", Path.of("/workspace/project"), 10_000, 8
        );
        assertTrue(second.activateSkill("review").contains("activated"));
        assertTrue(second.resume(idWithoutSkill).contains("scheduled"));

        assertEquals("bounded working summary", secondCompressor.lastSummary());
        assertFalse(second.promptContext().contains("Active skill 'review'"));
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

    @Test void newSessionGetsANewIdAndClearsSessionState() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        SessionStore sessions = new SessionStore(temporaryDirectory, mapper);
        SkillStore skills = new SkillStore(temporaryDirectory);
        skills.save("review", "---\ndescription: Review\n---\nCheck carefully");
        ContextCompressor compressor = new ContextCompressor(10_000, mapper);
        PersistentAgentMemory memory = new PersistentAgentMemory(
                sessions, compressor, skills, mapper, "model",
                Path.of("/workspace/project"), 10_000, 8
        );
        memory.begin("old");
        String oldId = sessions.currentMeta().id();
        memory.activateSkill("review");
        compressor.addTokens(100, 20);

        String result = memory.startNewSession();

        assertTrue(result.startsWith("New session started:"));
        assertNotEquals(oldId, sessions.currentMeta().id());
        assertEquals(0, compressor.usedTokens());
        assertFalse(memory.promptContext().contains("Active skill"));
        assertEquals("closed", sessions.listSessions().stream()
                .filter(session -> session.id().equals(oldId)).findFirst().orElseThrow().status());
    }

    @Test void manualCompressionRemainsPendingUntilHistoryIsEligible() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        PersistentAgentMemory memory = memory(
                new SessionStore(temporaryDirectory, mapper),
                new SkillStore(temporaryDirectory),
                mapper
        );
        memory.requestCompression();

        assertTrue(memory.shouldCompress());
        assertNull(memory.compressionPrompt(List.of(
                ChatMessage.system("s"), ChatMessage.user("u"), ChatMessage.assistant("a")
        ), "task"));
        assertTrue(memory.compressionPending());
        assertNotNull(memory.compressionPrompt(List.of(
                ChatMessage.system("s"), ChatMessage.user("old"),
                ChatMessage.assistant("old answer"), ChatMessage.user("r1"),
                ChatMessage.assistant("r2"), ChatMessage.user("r3"),
                ChatMessage.assistant("r4")
        ), "task"));
        assertFalse(memory.compressionPending());
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
