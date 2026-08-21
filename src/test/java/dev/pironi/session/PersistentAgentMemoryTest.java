package dev.pironi.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.pironi.model.ChatMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

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
        assertEquals("Skill saved: lesson", memory.saveLastTurnAsSkill("lesson"));
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

        assertEquals("Skill saved: status-flow", memory.saveLastTurnAsSkill("status-flow"));
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
        memory.saveSkill(
                "kept-across-sessions", "Workflow", List.of("Step"),
                List.of("workflow"), List.of(), "requested"
        );
        compressor.addTokens(100, 20);

        String result = memory.startNewSession();

        assertTrue(result.startsWith("New session started:"));
        assertNotEquals(oldId, sessions.currentMeta().id());
        assertEquals(0, compressor.usedTokens());
        assertFalse(memory.promptContext().contains("Active skill"));
        // A saved skill is durable by design: a new session clears the active one, not the file.
        assertTrue(skills.load("kept-across-sessions").isPresent());
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

    @Test void askingForASkillWritesItAndAskingAgainReplacesIt() throws Exception {
        // A draft that needed a slash command to accept died with the session, and the command
        // refused the name the agent told the user to type. Saying "change it" costs one turn.
        ObjectMapper mapper = new ObjectMapper();
        SkillStore skills = new SkillStore(temporaryDirectory);
        PersistentAgentMemory memory = memory(
                new SessionStore(temporaryDirectory, mapper), skills, mapper
        );

        String saved = memory.saveSkill(
                "weekly-status", "Prepare weekly status reports",
                List.of("Collect owners and blockers", "Verify totals"),
                List.of("weekly status report"), List.of("one-off incident"), "requested"
        );

        assertEquals("Skill saved: weekly-status", saved);
        assertTrue(skills.load("weekly-status").orElseThrow().contains("Collect owners"));

        String updated = memory.saveSkill(
                "weekly-status", "Prepare weekly status reports",
                List.of("Ask the owners directly"), List.of("weekly status report"),
                List.of(), "corrected"
        );

        assertEquals("Skill updated (previous version archived): weekly-status", updated);
        assertTrue(skills.load("weekly-status").orElseThrow().contains("Ask the owners directly"));
        assertTrue(Files.walk(temporaryDirectory.resolve("skills/.archive/versions"))
                .anyMatch(path -> path.getFileName().toString().equals("SKILL.md")));
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

        assertTrue(memory.saveSkill(
                "SOUL", "Change identity", List.of("Rename agent"),
                List.of("identity"), List.of(), "requested"
        ).contains("not adaptive"));

        assertTrue(memory.saveSkill(
                "existing", "Existing workflow", List.of("Use PASSWORD=do-not-store"),
                List.of("existing workflow"), List.of(), "Bearer bearer-secret"
        ).startsWith("Skill updated"));
        String written = skills.load("existing").orElseThrow();
        assertFalse(written.contains("do-not-store"));
        assertFalse(written.contains("bearer-secret"));
        assertTrue(written.contains("[REDACTED]"));
        assertTrue(Files.walk(temporaryDirectory.resolve("skills/.archive/versions"))
                .anyMatch(path -> path.getFileName().toString().equals("SKILL.md")));
    }

    @Test void onlyWhatWasNominatedAsDurableIsWrittenDown() throws Exception {
        // The per-turn finding is working memory for one task; the file is read by every future
        // session against this directory, so it takes only what the run said would keep.
        ObjectMapper mapper = new ObjectMapper();
        Path workspace = Files.createDirectory(temporaryDirectory.resolve("project"));
        FindingsStore store = new FindingsStore(temporaryDirectory);
        PersistentAgentMemory memory = memoryWith(store, workspace, mapper, () -> workspace);

        memory.rememberFindings(List.of("the build here is Maven"));

        List<FindingsStore.Finding> stored = store.load(workspace);
        assertEquals(List.of("the build here is Maven"),
                stored.stream().map(FindingsStore.Finding::text).toList());
        assertEquals(java.time.LocalDate.now().toString(), stored.getFirst().date());
        assertFalse(stored.getFirst().session().isBlank(), "the origin session must be recorded");
    }

    @Test void findingsAreFiledUnderTheDirectoryTheSessionIsInNow() throws Exception {
        // /workspace can move a session mid-task; what it learns afterwards belongs to the
        // directory it is in, not to the one it started in.
        ObjectMapper mapper = new ObjectMapper();
        Path first = Files.createDirectory(temporaryDirectory.resolve("first"));
        Path second = Files.createDirectory(temporaryDirectory.resolve("second"));
        FindingsStore store = new FindingsStore(temporaryDirectory);
        AtomicReference<Path> where = new AtomicReference<>(first);
        PersistentAgentMemory memory = memoryWith(store, first, mapper, where::get);

        memory.rememberFindings(List.of("first project uses Gradle"));
        where.set(second);
        memory.rememberFindings(List.of("second project uses Maven"));

        assertEquals(List.of("first project uses Gradle"),
                store.load(first).stream().map(FindingsStore.Finding::text).toList());
        assertEquals(List.of("second project uses Maven"),
                store.load(second).stream().map(FindingsStore.Finding::text).toList());
    }

    @Test void clearingFindingsRemovesWhatEarlierRunsEstablished() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Path workspace = Files.createDirectory(temporaryDirectory.resolve("cleared"));
        FindingsStore store = new FindingsStore(temporaryDirectory);
        store.save(workspace, List.of("a stale conclusion"), "2026-07-01", "old-session");
        PersistentAgentMemory memory = memoryWith(store, workspace, mapper, () -> workspace);

        assertEquals(1, memory.storedFindings().size());
        assertTrue(memory.forgetFindings());
        assertEquals(List.of(), memory.storedFindings());
        assertFalse(memory.forgetFindings());
    }

    private PersistentAgentMemory memoryWith(FindingsStore store, Path workspace,
            ObjectMapper mapper, java.util.function.Supplier<Path> source) throws Exception {
        PersistentAgentMemory memory = new PersistentAgentMemory(
                new SessionStore(temporaryDirectory, mapper),
                new ContextCompressor(10_000, mapper), new SkillStore(temporaryDirectory), mapper,
                "model", workspace, 10_000, 8, store
        );
        memory.useWorkspaceSource(source);
        return memory;
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
