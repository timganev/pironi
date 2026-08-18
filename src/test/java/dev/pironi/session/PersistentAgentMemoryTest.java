package dev.pironi.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.pironi.model.ChatMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PersistentAgentMemoryTest {
    @TempDir Path temporaryDirectory;

    @Test void carriesTheConversationIntoTheNextTaskOfTheSameSession() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        SessionStore sessions = new SessionStore(temporaryDirectory, mapper);
        SkillStore skills = new SkillStore(temporaryDirectory);
        PersistentAgentMemory memory = memory(sessions, skills, mapper);
        memory.begin("export the mailbox and report hours per project");
        memory.checkpoint(List.of(
                ChatMessage.system("system"),
                ChatMessage.user("export the mailbox and report hours per project"),
                ChatMessage.user("tool result: Apollo 210, Borealis 60"),
                ChatMessage.assistant("Apollo took most of the week")
        ), "export the mailbox and report hours per project");

        List<ChatMessage> carried = memory.begin("same thing, as a table");

        assertEquals(4, carried.size());
        assertEquals("tool result: Apollo 210, Borealis 60", carried.get(2).content());
    }

    @Test void clearedCarryOverStartsTheNextTaskFromNothing() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        SessionStore sessions = new SessionStore(temporaryDirectory, mapper);
        SkillStore skills = new SkillStore(temporaryDirectory);
        PersistentAgentMemory memory = memory(sessions, skills, mapper);
        memory.begin("goal");
        memory.checkpoint(List.of(ChatMessage.system("system"), ChatMessage.user("goal")), "goal");

        memory.clearCarryOver();

        assertTrue(memory.begin("unrelated").isEmpty());
    }

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
        assertTrue(memory.saveLastTurnAsSkill("lesson").contains("not saved"));
        assertTrue(skills.load("lesson").isEmpty());
        assertEquals("Skill accepted and saved: lesson", memory.acceptPendingSkill());
        assertTrue(skills.load("lesson").orElseThrow().contains("fix bug"));
        assertEquals("Active skill cleared.", memory.activateSkill("off"));
    }

    @Test void savedSkillExcludesShellConversationWrapper() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        SkillStore skills = new SkillStore(temporaryDirectory);
        PersistentAgentMemory memory = memory(
                new SessionStore(temporaryDirectory, mapper), skills, mapper
        );
        memory.completed("""
                User: remember ORION-742
                Pironi: acknowledged
                Current request:
                Use Marina as owner for the Friday status workflow
                """, "Workflow verified");

        assertTrue(memory.saveLastTurnAsSkill("status-flow").contains("not saved"));
        assertEquals("Skill accepted and saved: status-flow", memory.acceptPendingSkill());
        String saved = skills.load("status-flow").orElseThrow();
        assertTrue(saved.contains("Use Marina as owner"));
        assertFalse(saved.contains("ORION-742"));
        assertFalse(saved.contains("acknowledged"));
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
        memory.proposeSkill(
                "temporary-draft", "Temporary workflow", List.of("Step"),
                List.of("temporary workflow"), List.of(), "Explicit correction"
        );
        compressor.addTokens(100, 20);

        String result = memory.startNewSession();

        assertTrue(result.startsWith("New session started:"));
        assertNotEquals(oldId, sessions.currentMeta().id());
        assertEquals(0, compressor.usedTokens());
        assertFalse(memory.promptContext().contains("Active skill"));
        assertEquals("No pending skill draft.", memory.pendingSkill());
        assertEquals("closed", sessions.listSessions().stream()
                .filter(session -> session.id().equals(oldId)).findFirst().orElseThrow().status());
    }

    @Test void currentSessionIdCreatesSeamlessIdempotentSession() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        SessionStore sessions = new SessionStore(temporaryDirectory, mapper);
        SkillStore skills = new SkillStore(temporaryDirectory);
        ContextCompressor compressor = new ContextCompressor(10_000, mapper);
        PersistentAgentMemory memory = new PersistentAgentMemory(
                sessions, compressor, skills, mapper, "model",
                Path.of("/workspace/project"), 10_000, 8
        );
        // No session exists yet -> currentSessionId must lazily create one.
        String firstId = memory.currentSessionId();
        assertFalse(firstId.isBlank());
        // Calling again must return the SAME id (idempotent, no duplicate session).
        assertEquals(firstId, memory.currentSessionId());
        assertEquals(1, sessions.listSessions().size());
        assertEquals("active", sessions.listSessions().getFirst().status());
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

    @Test void proposalIsEphemeralUntilAcceptedAndCanBeRejected() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        SkillStore skills = new SkillStore(temporaryDirectory);
        PersistentAgentMemory memory = memory(
                new SessionStore(temporaryDirectory, mapper), skills, mapper
        );
        String proposed = memory.proposeSkill(
                "weekly-status", "Prepare weekly status reports",
                List.of("Collect owners and blockers", "Verify totals"),
                List.of("weekly status report"), List.of("one-off incident"),
                "The user explicitly corrected the workflow"
        );

        assertTrue(proposed.contains("not saved"));
        assertTrue(skills.load("weekly-status").isEmpty());
        assertTrue(memory.pendingSkill().contains("Collect owners"));
        assertEquals("Skill draft rejected: weekly-status", memory.rejectPendingSkill());
        assertTrue(skills.load("weekly-status").isEmpty());

        memory.proposeSkill(
                "weekly-status", "Prepare weekly status reports",
                List.of("Collect owners and blockers"), List.of("weekly status report"),
                List.of(), "Explicit correction"
        );
        assertEquals("Skill accepted and saved: weekly-status", memory.acceptPendingSkill());
        assertTrue(skills.load("weekly-status").isPresent());
    }

    @Test void automaticSelectionLoadsOneRelevantSkillAndOffSuppressesIt() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        SkillStore skills = new SkillStore(temporaryDirectory);
        skills.save("weekly-status", """
                ---
                description: Prepare weekly status report with owners and blockers
                ---
                Follow the verified workflow.
                """);
        PersistentAgentMemory memory = memory(
                new SessionStore(temporaryDirectory, mapper), skills, mapper
        );

        memory.begin("Prepare the weekly status report with owners");
        assertTrue(memory.promptContext().contains("weekly-status"));
        assertEquals("Active skill cleared.", memory.activateSkill("off"));
        memory.begin("Prepare the weekly status report with owners");
        assertEquals("", memory.promptContext());
        assertEquals("Automatic skill selection enabled.", memory.activateSkill("auto"));
        memory.begin("Prepare the weekly status report with owners");
        assertTrue(memory.promptContext().contains("weekly-status"));
    }

    @Test void draftRedactsSecretsRefusesIdentityNamesAndExistingSkillOverwrite() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        SkillStore skills = new SkillStore(temporaryDirectory);
        skills.save("existing", "---\ndescription: Existing workflow\n---\nOriginal");
        PersistentAgentMemory memory = memory(
                new SessionStore(temporaryDirectory, mapper), skills, mapper
        );

        assertTrue(memory.proposeSkill(
                "SOUL", "Change identity", List.of("Rename agent"),
                List.of("identity"), List.of(), "User correction"
        ).contains("not adaptive"));
        assertTrue(memory.proposeSkill(
                "existing", "Existing workflow", List.of("Use PASSWORD=do-not-store"),
                List.of("existing workflow"), List.of(), "Bearer bearer-secret"
        ).contains("not saved"));
        String preview = memory.pendingSkill();
        assertFalse(preview.contains("do-not-store"));
        assertFalse(preview.contains("bearer-secret"));
        assertTrue(preview.contains("[REDACTED]"));
        assertTrue(memory.acceptPendingSkill().startsWith(
                "Skill already exists and was not overwritten: existing"
        ));
        assertTrue(skills.load("existing").orElseThrow().contains("Original"));
        assertEquals(
                "Skill accepted, previous version archived, and replaced: existing",
                memory.acceptPendingSkill(true)
        );
        assertTrue(skills.load("existing").orElseThrow().contains("[REDACTED]"));
        assertTrue(Files.walk(temporaryDirectory.resolve("skills/.archive/versions"))
                .anyMatch(path -> path.getFileName().toString().equals("SKILL.md")));
    }

    @Test void saveSkillDoesNotReplaceBetterPendingProposal() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        PersistentAgentMemory memory = memory(
                new SessionStore(temporaryDirectory, mapper),
                new SkillStore(temporaryDirectory), mapper
        );
        memory.completed("generic request", "generic answer");
        memory.proposeSkill(
                "quality-draft", "Specific corrected workflow",
                List.of("First precise step", "Second precise step"),
                List.of("specific workflow"), List.of(), "Explicit user correction"
        );

        assertTrue(memory.saveLastTurnAsSkill("generic").contains("already pending"));
        String preview = memory.pendingSkill();
        assertTrue(preview.contains("First precise step"));
        assertFalse(preview.contains("generic answer"));
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
