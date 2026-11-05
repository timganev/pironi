package dev.pironi.swarm;

import dev.pironi.model.ChatMessage;
import dev.pironi.model.ModelResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DispatchingModelClientTest {

    private static String chars(int n) {
        return "x".repeat(n);
    }

    @Test
    void onlyWhatAConversationAddsSinceItsLastRequestCounts() {
        var client = new DispatchingModelClient(m -> null, new DispatchController(64, 16_000), null, null);
        List<ChatMessage> turn = new ArrayList<>(List.of(ChatMessage.system(chars(3_500)), ChatMessage.user(chars(3_500))));
        assertEquals(2_000, client.uncachedTokens(turn), "a new conversation is all uncached");
        turn.add(ChatMessage.assistant(chars(350)));
        turn.add(ChatMessage.user(chars(350)));
        assertEquals(200, client.uncachedTokens(turn), "the earlier turn is in the prefix cache");
    }

    @Test
    void anotherConversationPaysOnlyForWhereItDiverges() {
        var client = new DispatchingModelClient(m -> null, new DispatchController(64, 16_000), null, null);
        client.uncachedTokens(List.of(ChatMessage.system(chars(3_500)), ChatMessage.user("task A")));
        // Same system prompt and the same "task " - only the last letter is new.
        assertEquals(1, client.uncachedTokens(List.of(ChatMessage.system(chars(3_500)), ChatMessage.user("task B"))));
    }

    @Test
    void anUnrelatedConversationIsCountedWhole() {
        var client = new DispatchingModelClient(m -> null, new DispatchController(64, 16_000), null, null);
        client.uncachedTokens(List.of(ChatMessage.system("y".repeat(3_500)), ChatMessage.user("task A")));
        assertEquals(1_002, client.uncachedTokens(List.of(ChatMessage.system(chars(3_500)), ChatMessage.user("task B"))));
    }

    @Test
    void everyCallTakesAndReturnsAPermit() throws Exception {
        DispatchController controller = new DispatchController(1, 16_000);
        AtomicInteger seenInFlight = new AtomicInteger();
        ModelResponse answer = new ModelResponse("ok", 1, 1, 0, 0, "stop", "text", 1, "", "", 0);
        var client = new DispatchingModelClient(m -> {
            seenInFlight.set(controller.inFlight());
            return answer;
        }, controller, null, null);
        assertSame(answer, client.chat(List.of(ChatMessage.user("hi"))));
        assertEquals(1, seenInFlight.get());
        assertEquals(0, controller.inFlight(), "the permit was not returned");
        client.chatText(List.of(ChatMessage.user("hi")));
        assertEquals(0, controller.inFlight());
    }

    @Test
    void withoutTheFlagTheClientIsUntouched() {
        dev.pironi.model.ModelClient plain = m -> null;
        assertSame(plain, DispatchingModelClient.wrap(plain, SwarmSettings.off(),
                java.net.URI.create("http://127.0.0.1:8000/v1"), "k", "m"));
    }

    @Test
    void llamaCppSlotsBecomeTheCeiling() {
        assertEquals(4, DispatchingModelClient.slotsIn("{\"total_slots\":4,\"model_path\":\"m.gguf\"}"));
        assertEquals(0, DispatchingModelClient.slotsIn("{\"model\":\"other server\"}"));
        assertEquals(0, DispatchingModelClient.slotsIn("not json"));
    }

    @Test
    void aServerWithoutPropsLeavesTheSafetyCeiling() {
        // Nothing listens on port 9: the server told nothing, and the safety cap applies.
        assertEquals(0, DispatchingModelClient.serverSlots(java.net.URI.create("http://127.0.0.1:9/v1"), null));
    }

    @Test
    void fullServersAreToldApartFromBadRequests() {
        assertTrue(DispatchingModelClient.overloaded(new java.io.IOException("Provider returned HTTP 429: slow down")));
        assertTrue(DispatchingModelClient.overloaded(new java.io.IOException("Provider returned HTTP 503: busy")));
        assertTrue(DispatchingModelClient.overloaded(
                new java.io.IOException("send failed", new java.net.http.HttpTimeoutException("request timed out"))));
        assertFalse(DispatchingModelClient.overloaded(new java.io.IOException("Provider returned HTTP 400: bad schema")));
    }

    @Test
    void aLearnedLimitIsKeptPerServerAndModel(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        SwarmProfile p = new SwarmProfile(dir.resolve("sub/swarm-profile.json"));
        String a = SwarmProfile.key(java.net.URI.create("http://127.0.0.1:8000/v1/"), "qwen36");
        String b = SwarmProfile.key(java.net.URI.create("http://127.0.0.1:8000/v1"), "qwen38");
        assertEquals(0, p.limit(a), "nothing learned yet");
        p.remember(a, 8);
        p.remember(b, 3);
        SwarmProfile again = new SwarmProfile(dir.resolve("sub/swarm-profile.json"));
        assertEquals(8, again.limit(a));
        assertEquals(3, again.limit(b));
    }

    @Test
    void anUnreadableProfileIsNothingLearned(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Path f = dir.resolve("swarm-profile.json");
        java.nio.file.Files.writeString(f, "not json");
        assertEquals(0, new SwarmProfile(f).limit("x"));
        assertEquals(0, new SwarmProfile(null).limit("x"));
    }

    @Test
    void aLearnedLimitIsAWarmStartAndNothingLearnedACold(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        // Port 9: nothing listens, so /props answers nothing and no slots are known.
        java.net.URI base = java.net.URI.create("http://127.0.0.1:9/v1");
        java.nio.file.Path f = dir.resolve("swarm-profile.json");
        new SwarmProfile(f).remember(SwarmProfile.key(base, "m"), 6);
        var settings = new SwarmSettings(true, 0, SwarmSettings.DEFAULT_BUDGET_TOKENS, true, null, f);
        var warm = (DispatchingModelClient) DispatchingModelClient.wrap(m -> null, settings, base, null, "m");
        var cold = (DispatchingModelClient) DispatchingModelClient.wrap(m -> null, settings, base, null, "other");
        try {
            assertEquals(6, warm.controller().window());
            assertFalse(warm.controller().slowStart(), "a learned start doubles");
            assertEquals(ConcurrencyLimiter.BLIND_START, cold.controller().window());
            assertTrue(cold.controller().slowStart());
        } finally {
            warm.close();
            cold.close();
        }
    }

    @Test
    void theOrchestratorDoesNotSplitAtALimitOfOne() {
        var client = new DispatchingModelClient(m -> null, new DispatchController(1, 16_000), null, null);
        assertEquals(1, client.parallelism().limit());
        assertFalse(client.parallelism().worthSplitting());
        assertTrue(new Parallelism(4, true, 8).worthSplitting());
    }
}
