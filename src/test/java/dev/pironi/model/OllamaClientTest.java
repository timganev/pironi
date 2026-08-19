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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
                    {"message":{"role":"assistant","content":"\\\"ok\\\"}"},"done":true,"done_reason":"stop","prompt_eval_count":12,"eval_count":8,"total_duration":99,"eval_duration":50}
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
            assertEquals("stop", response.finishReason());
            assertEquals("json_schema", response.responseFormat());
            assertEquals(true, requestBody.get().path("stream").asBoolean());
            assertFalse(requestBody.get().path("think").asBoolean());
            assertEquals("object", requestBody.get().path("format").path("type").asText());
            assertEquals(false, requestBody.get().path("format").path("additionalProperties").asBoolean());
            assertEquals(8_192, requestBody.get().path("options").path("num_ctx").asInt());
            assertEquals(512, requestBody.get().path("options").path("num_predict").asInt());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void retriesServerErrorsAndReportsTheAttemptsWhenTheyPersist() throws Exception {
        // A runner that dies mid-generation does not break the socket: Ollama answers 500 with
        // the runner's error in the body. That is a completed exchange, so it reached none of
        // the retrying and ended a run that had 22 turns of work behind it.
        AtomicInteger attempts = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body;
            int status;
            if (attempts.incrementAndGet() < 3) {
                status = 500;
                body = "{\"error\":\"EOF\"}".getBytes(StandardCharsets.UTF_8);
            } else {
                status = 200;
                body = """
                        {"message":{"role":"assistant","content":"{\\"finalAnswer\\":\\"ok\\"}"},"done":true,"done_reason":"stop"}
                        """.getBytes(StandardCharsets.UTF_8);
            }
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            OllamaClient client = client(server.getAddress().getPort());

            ModelResponse response = client.chat(List.of(ChatMessage.user("hello")));

            assertEquals("{\"finalAnswer\":\"ok\"}", response.content());
            assertEquals(3, attempts.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void clientErrorsAreNotRetried() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            exchange.getRequestBody().readAllBytes();
            attempts.incrementAndGet();
            byte[] body = "{\"error\":\"model not found\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(404, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            OllamaClient client = client(server.getAddress().getPort());

            java.io.IOException error = org.junit.jupiter.api.Assertions.assertThrows(
                    java.io.IOException.class,
                    () -> client.chat(List.of(ChatMessage.user("hello")))
            );

            assertTrue(error.getMessage().contains("HTTP 404"), error.getMessage());
            assertEquals(1, attempts.get(), "a rejected request is the caller's fault, not transient");
        } finally {
            server.stop(0);
        }
    }

    private OllamaClient client(int port) {
        return new OllamaClient(
                HttpClient.newHttpClient(),
                objectMapper,
                URI.create("http://127.0.0.1:" + port + "/api/chat"),
                "test-model",
                Duration.ofSeconds(5),
                8_192,
                512
        );
    }
}
