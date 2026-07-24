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
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class OllamaClientTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void disablesThinkingAndLimitsPrediction() throws Exception {
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            requestBody.set(objectMapper.readTree(exchange.getRequestBody()));
            byte[] response = """
                    {
                      "message":{"role":"assistant","content":"{\\"finalAnswer\\":\\"ok\\"}"},
                      "prompt_eval_count":12,
                      "eval_count":8,
                      "total_duration":99
                    }
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

            ModelResponse response = client.chat(List.of(ChatMessage.user("hello")));

            assertEquals("{\"finalAnswer\":\"ok\"}", response.content());
            assertFalse(requestBody.get().path("think").asBoolean());
            assertEquals(8_192, requestBody.get().path("options").path("num_ctx").asInt());
            assertEquals(512, requestBody.get().path("options").path("num_predict").asInt());
        } finally {
            server.stop(0);
        }
    }
}
