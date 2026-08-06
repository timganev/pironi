package dev.pironi.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class OllamaClientTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void streamsContentAndLimitsPrediction() throws Exception {
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            requestBody.set(objectMapper.readTree(exchange.getRequestBody()));
            byte[] response = """
                    {"message":{"role":"assistant","content":"{\\\"finalAnswer\\\":"},"done":false}
                    {"message":{"role":"assistant","content":"\\\"ok\\\"}"},"done":true,"prompt_eval_count":12,"eval_count":8,"total_duration":99,"eval_duration":50}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            OllamaClient client = new OllamaClient(
                    HttpClient.newHttpClient(),
                    objectMapper,
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/chat"),
                    "test-model",
                    Duration.ofSeconds(5),
                    8_192,
                    512
            );
            List<String> chunks = new ArrayList<>();

            ModelResponse response = client.chatStreaming(
                    List.of(ChatMessage.user("hello")),
                    chunks::add
            );

            assertEquals("{\"finalAnswer\":\"ok\"}", response.content());
            assertEquals(List.of("{\"finalAnswer\":", "\"ok\"}"), chunks);
            assertEquals(12, response.promptTokens());
            assertEquals(8, response.outputTokens());
            assertEquals(99, response.durationNanos());
            assertEquals(50, response.evalDurationNanos());
            assertEquals(true, requestBody.get().path("stream").asBoolean());
            assertFalse(requestBody.get().path("think").asBoolean());
            assertEquals(8_192, requestBody.get().path("options").path("num_ctx").asInt());
            assertEquals(512, requestBody.get().path("options").path("num_predict").asInt());
        } finally {
            server.stop(0);
        }
    }
}
