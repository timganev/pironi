package dev.pironi.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.pironi.model.ChatMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SessionStoreTest {
    @TempDir Path temporaryDirectory;

    @Test void persistsTurnsMetadataCheckpointAndDeletion() throws Exception {
        SessionStore store = new SessionStore(temporaryDirectory, new ObjectMapper());
        var meta = store.startSession("model", Path.of("/workspace/project"), 1000, 8);
        store.appendTurn(ChatMessage.user("hello"), 11, 7);
        store.appendToolResult("read_file", new ObjectMapper().createObjectNode(), "data");
        store.saveCheckpoint("{\"version\":1}");
        store.saveMeta();
        store.updateStatus("completed");

        assertEquals(18, store.currentMeta().totalTokens());
        assertEquals(meta.id(), store.latestSessionId().orElseThrow());
        assertEquals(2, store.readSessionMessages(meta.id()).size());
        assertTrue(store.loadCheckpoint(meta.id()).orElseThrow().contains("version"));
        assertEquals(1, store.listSessions().size());
        assertEquals("completed", store.listSessions().getFirst().status());
        assertTrue(store.deleteSession(meta.id()));
        assertFalse(store.deleteSession(meta.id()));
    }

    @Test void createsUniqueIdsAndRejectsUnsafeIds() throws Exception {
        SessionStore store = new SessionStore(temporaryDirectory, new ObjectMapper());
        String first = store.startSession("m", Path.of("/w/p"), 10, 1).id();
        String second = store.startSession("m", Path.of("/w/p"), 10, 1).id();
        assertNotEquals(first, second);
        assertTrue(store.loadCheckpoint("../escape").isEmpty());
        assertFalse(store.deleteSession("../escape"));
        assertTrue(store.readSessionMessages("../escape").isEmpty());
        assertFalse(Files.exists(temporaryDirectory.resolve("escape.ckpt.json")));
    }

    @Test void redactsSecretsFromTurnsToolsAndCheckpoints() throws Exception {
        SessionStore store = new SessionStore(temporaryDirectory, new ObjectMapper());
        var meta = store.startSession("model", Path.of("/workspace/project"), 1000, 8);
        store.appendTurn(ChatMessage.user(
                "DEEPSEEK_API_KEY=deep-secret password: hunter2"), 1, 1);
        var args = new ObjectMapper().createObjectNode()
                .put("authorization", "Bearer bearer-secret");
        store.appendToolResult("run_command", args, "OPENAI_API_KEY=tool-secret");
        store.saveCheckpoint("""
                {"version":1,"messages":[{"role":"user",
                "content":"token=checkpoint-secret"}]}
                """);

        String persisted = String.join("\n", store.readSessionMessages(meta.id()))
                + store.loadCheckpoint(meta.id()).orElseThrow();
        assertFalse(persisted.contains("deep-secret"));
        assertFalse(persisted.contains("hunter2"));
        assertFalse(persisted.contains("bearer-secret"));
        assertFalse(persisted.contains("tool-secret"));
        assertFalse(persisted.contains("checkpoint-secret"));
        assertTrue(persisted.contains("[REDACTED]"));
        assertDoesNotThrow(() -> new ObjectMapper().readTree(
                store.loadCheckpoint(meta.id()).orElseThrow()
        ));
    }

    @Test void redactedCheckpointWithEscapedNewlinesRemainsValidJson() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        SessionStore store = new SessionStore(temporaryDirectory, mapper);
        var meta = store.startSession("model", Path.of("/workspace/project"), 1000, 8);
        var root = mapper.createObjectNode().put("version", 1);
        root.putArray("messages").addObject()
                .put("role", "user")
                .put("content", "first line\nPASSWORD=secret-value\nlast line");

        store.saveCheckpoint(root.toString());

        String checkpoint = store.loadCheckpoint(meta.id()).orElseThrow();
        assertFalse(checkpoint.contains("secret-value"));
        assertEquals(
                "first line\nPASSWORD=[REDACTED]\nlast line",
                mapper.readTree(checkpoint).path("messages").get(0).path("content").asText()
        );
    }

    @Test
    void prunesTranscriptsOutsideTheRetentionWindow() throws Exception {
        // A transcript holds the whole conversation, including what the agent read on the way.
        // Keeping every one for ever is an archive nobody asked for.
        SessionStore store = new SessionStore(temporaryDirectory, new ObjectMapper());
        store.startSession("model", Path.of("/tmp/project"), 10_000, 8);
        store.appendTurn(ChatMessage.user("hello"), 3, 2);
        Path sessions = temporaryDirectory.resolve("sessions");
        Path old = Files.createFile(sessions.resolve("2026-01-01T0000-old-1111.jsonl"));
        Files.createFile(sessions.resolve("2026-01-01T0000-old-1111.meta.json"));
        Files.setLastModifiedTime(old, java.nio.file.attribute.FileTime.fromMillis(
                System.currentTimeMillis() - java.time.Duration.ofDays(90).toMillis()));

        assertEquals(1, store.pruneOlderThan(java.time.Duration.ofDays(30)));

        assertFalse(Files.exists(old));
        assertFalse(Files.exists(sessions.resolve("2026-01-01T0000-old-1111.meta.json")));
        try (var remaining = Files.list(sessions)) {
            assertEquals(1, remaining.filter(p -> p.toString().endsWith(".jsonl")).count(),
                    "the running session survives its own pruning");
        }
    }

    @Test
    void continuingTakesTheNewestResumableSessionInThisWorkspaceOnly() throws Exception {
        // The newest session overall belongs to whatever ran last, which may be another project;
        // and a session is recorded the moment it starts, so the newest often holds nothing.
        SessionStore store = new SessionStore(temporaryDirectory, new ObjectMapper());

        store.startSession("m", Path.of("/work/one"), 1000, 8);
        store.saveCheckpoint("{\"version\":1,\"messages\":[]}");
        store.saveMeta();
        String wanted = store.currentMeta().id();

        Thread.sleep(1_100);
        store.startSession("m", Path.of("/work/one"), 1000, 8);
        store.saveMeta();

        Thread.sleep(1_100);
        store.startSession("m", Path.of("/work/other"), 1000, 8);
        store.saveCheckpoint("{\"version\":1,\"messages\":[]}");
        store.saveMeta();

        assertEquals(wanted, store.latestSessionId("/work/one").orElseThrow());
        assertTrue(store.latestSessionId("/work/nothing-here").isEmpty());
    }
}
