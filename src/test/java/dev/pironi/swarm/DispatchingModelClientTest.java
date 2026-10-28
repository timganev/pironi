package dev.pironi.swarm;

import dev.pironi.model.ChatMessage;
import dev.pironi.model.ModelResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

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
                java.net.URI.create("http://127.0.0.1:8000/v1"), "k"));
    }
}
