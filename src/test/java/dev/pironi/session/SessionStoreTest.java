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
}
