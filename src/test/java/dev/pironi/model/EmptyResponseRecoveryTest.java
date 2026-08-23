package dev.pironi.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A thinking model can spend its whole output budget reasoning and never begin the answer. The
 * response then carries reasoning_content, no content, and finish_reason=length - and retrying
 * the identical request did the identical thing three times before reporting a provider fault
 * that was not one.
 */
class EmptyResponseRecoveryTest {

    @Test
    void raisesTheOutputBudgetWhenReasoningAteAllOfIt() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<Integer> budgets = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            var request = mapper.readTree(exchange.getRequestBody());
            budgets.add(request.path("max_tokens").asInt());
            String body = budgets.size() < 2
                    ? "{\"choices\":[{\"finish_reason\":\"length\",\"message\":"
                            + "{\"role\":\"assistant\",\"content\":\"\","
                            + "\"reasoning_content\":\"still thinking\"}}]}"
                    : "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":"
                            + "{\"role\":\"assistant\",\"content\":\"the answer\"}}]}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var client = OpenAiCompatibleClient.deepSeek(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    "deepseek-v4-flash", "key", Duration.ofSeconds(5), 4096);

            ModelResponse response = client.chatText(List.of(ChatMessage.user("go")));

            assertEquals("the answer", response.content());
            assertEquals(2, budgets.size());
            assertEquals(4096, budgets.get(0));
            assertEquals(8192, budgets.get(1), "the second attempt must ask for more room");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void saysWhyItGaveUpRatherThanJustSayingEmpty() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            String body = "{\"usage\":{\"prompt_tokens\":24892,\"completion_tokens\":4096},"
                    + "\"choices\":[{\"finish_reason\":\"length\",\"message\":"
                    + "{\"role\":\"assistant\",\"content\":\"\","
                    + "\"reasoning_content\":\"still thinking\"}}]}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var client = OpenAiCompatibleClient.deepSeek(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    "deepseek-v4-flash", "key", Duration.ofSeconds(5), 4096);

            Exception failure = assertThrows(Exception.class,
                    () -> client.chatText(List.of(ChatMessage.user("go"))));

            String message = failure.getMessage();
            assertTrue(message.contains("finish_reason=length"), message);
            assertTrue(message.contains("promptTokens=24892"), message);
            assertTrue(message.contains("reasoning_content"), message);
        } finally {
            server.stop(0);
        }
    }
}
