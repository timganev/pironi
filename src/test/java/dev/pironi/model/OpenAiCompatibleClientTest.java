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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OpenAiCompatibleClientTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void sendsCompatibleRequestAndParsesResponse() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(objectMapper.readTree(exchange.getRequestBody()));
            byte[] response = """
                    {
                      "choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"{\\"finalAnswer\\":\\"ok\\"}"}}],
                      "usage":{"prompt_tokens":11,"completion_tokens":7}
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            URI endpoint = URI.create(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions"
            );
            OpenAiCompatibleClient client = new OpenAiCompatibleClient(
                    HttpClient.newHttpClient(),
                    objectMapper,
                    endpoint,
                    "test-model",
                    "test-key",
                    Duration.ofSeconds(5),
                    123
            );

            ModelResponse response = client.chat(List.of(ChatMessage.user("hello")));

            assertEquals("{\"finalAnswer\":\"ok\"}", response.content());
            assertEquals(11, response.promptTokens());
            assertEquals(7, response.outputTokens());
            assertEquals("stop", response.finishReason());
            assertEquals("json_schema", response.responseFormat());
            assertEquals(false, response.truncated());
            assertEquals("Bearer test-key", authorization.get());
            assertEquals("test-model", requestBody.get().path("model").asText());
            assertEquals(123, requestBody.get().path("max_tokens").asInt());
            assertEquals("json_schema", requestBody.get().path("response_format").path("type").asText());
            assertEquals(
                    "pironi_agent_response",
                    requestBody.get().path("response_format").path("json_schema").path("name").asText()
            );
            assertTrue(requestBody.get().path("response_format").path("json_schema")
                    .path("schema").path("required").isArray());
            assertTrue(requestBody.get().path("messages").isArray());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void anUnguardedRequestCarriesNoReasoningLimits() throws Exception {
        List<JsonNode> requests = new java.util.concurrent.CopyOnWriteArrayList<>();
        HttpServer server = scriptedServer(requests, List.of("""
                {"choices":[{"finish_reason":"stop","message":{"content":"{\\"finalAnswer\\":\\"ok\\"}"}}]}
                """));
        try {
            client(server, ReasoningGuard.OFF).chat(List.of(ChatMessage.user("hello")));

            JsonNode body = requests.getFirst();
            assertTrue(body.path("thinking_token_budget").isMissingNode(), body.toString());
            assertTrue(body.path("repetition_detection").isMissingNode(), body.toString());
            assertTrue(body.path("chat_template_kwargs").isMissingNode(), body.toString());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aThinkingLoopIsAskedOnceMoreToActWithThinkingOff() throws Exception {
        List<JsonNode> requests = new java.util.concurrent.CopyOnWriteArrayList<>();
        HttpServer server = scriptedServer(requests, List.of("""
                {"choices":[{"finish_reason":"length","message":{"content":null,"reasoning":"hmm, hmm, hmm"}}],
                 "usage":{"prompt_tokens":10,"completion_tokens":8192}}
                """, """
                {"choices":[{"finish_reason":"stop","message":{"content":"{\\"finalAnswer\\":\\"done\\"}"}}]}
                """));
        try {
            ModelResponse response = client(server, new ReasoningGuard(4096, true))
                    .chat(List.of(ChatMessage.user("move the files")));

            assertEquals("{\"finalAnswer\":\"done\"}", response.content());
            assertEquals(2, response.requestAttempts());
            assertEquals("thinking-loop:length", response.fallbackFrom());
            JsonNode first = requests.get(0);
            assertEquals(4096, first.path("thinking_token_budget").asInt());
            assertEquals(4, first.path("repetition_detection").path("min_count").asInt());
            assertTrue(first.path("chat_template_kwargs").isMissingNode(), first.toString());
            JsonNode second = requests.get(1);
            assertEquals(false, second.path("chat_template_kwargs").path("enable_thinking").asBoolean(true));
            // The nudge goes last so the cached prefix of the first request still matches.
            assertEquals(first.path("messages").get(0), second.path("messages").get(0));
            JsonNode nudge = second.path("messages").get(second.path("messages").size() - 1);
            assertTrue(nudge.path("content").asText().contains("Stop thinking"), nudge.toString());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void anEmptyAnswerThatIsNotALoopIsNotRetried() throws Exception {
        List<JsonNode> requests = new java.util.concurrent.CopyOnWriteArrayList<>();
        HttpServer server = scriptedServer(requests, List.of("""
                {"choices":[{"finish_reason":"stop","message":{"content":""}}]}
                """, """
                {"choices":[{"finish_reason":"stop","message":{"content":"{\\"finalAnswer\\":\\"late\\"}"}}]}
                """));
        try {
            assertThrows(java.io.IOException.class, () -> client(server, new ReasoningGuard(4096, false))
                    .chat(List.of(ChatMessage.user("hello"))));
            assertEquals(1, requests.size());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aSecondLoopEndsTheTurnAfterOneRescue() throws Exception {
        List<JsonNode> requests = new java.util.concurrent.CopyOnWriteArrayList<>();
        String loop = """
                {"choices":[{"finish_reason":"repetition","message":{"content":"","reasoning":"again again"}}]}
                """;
        HttpServer server = scriptedServer(requests, List.of(loop, loop, loop));
        try {
            java.io.IOException error = assertThrows(java.io.IOException.class,
                    () -> client(server, new ReasoningGuard(0, true)).chat(List.of(ChatMessage.user("hi"))));
            assertEquals(2, requests.size());
            assertTrue(error.getMessage().contains("finish_reason=repetition"), error.getMessage());
        } finally {
            server.stop(0);
        }
    }

    private HttpServer scriptedServer(List<JsonNode> requests, List<String> replies) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.add(objectMapper.readTree(exchange.getRequestBody()));
            byte[] response = replies.get(Math.min(calls.getAndIncrement(), replies.size() - 1))
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static OpenAiCompatibleClient client(HttpServer server, ReasoningGuard guard) {
        return new OpenAiCompatibleClient(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"),
                "test-model", "test-key", Duration.ofSeconds(5), 8192, guard);
    }

    @Test
    void preservesApiVersionPathWhenBuildingChatEndpoint() throws Exception {
        AtomicReference<String> requestPath = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/chat/completions", exchange -> {
            requestPath.set(exchange.getRequestURI().getPath());
            byte[] response = """
                    {"choices":[{"message":{"content":"{\\"finalAnswer\\":\\"ok\\"}"}}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            URI baseUri = URI.create(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1"
            );
            OpenAiCompatibleClient client = new OpenAiCompatibleClient(
                    baseUri,
                    "openrouter/auto",
                    "openrouter-key",
                    Duration.ofSeconds(5),
                    512
            );

            client.chat(List.of(ChatMessage.user("hello")));

            assertEquals("/api/v1/chat/completions", requestPath.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sendsDeepSeekV4ProThinkingRequestAndParsesTypedOutput() throws Exception {
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            requestBody.set(objectMapper.readTree(exchange.getRequestBody()));
            byte[] response = """
                    {
                      "choices":[{
                        "message":{
                          "role":"assistant",
                          "content":[
                            {"type":"thinking","thinking":"private reasoning"},
                            {"type":"output","text":"{\\"finalAnswer\\":\\"done\\"}"}
                          ]
                        }
                      }],
                      "usage":{"prompt_tokens":19,"completion_tokens":13}
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            URI endpoint = URI.create(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions"
            );
            OpenAiCompatibleClient client = new OpenAiCompatibleClient(
                    HttpClient.newHttpClient(),
                    objectMapper,
                    endpoint,
                    "deepseek-v4-pro",
                    "deepseek-key",
                    Duration.ofSeconds(5),
                    8192,
                    true
            );

            ModelResponse response = client.chat(List.of(ChatMessage.user("return json")));

            assertEquals("{\"finalAnswer\":\"done\"}", response.content());
            assertEquals("deepseek-v4-pro", requestBody.get().path("model").asText());
            assertEquals(
                    "enabled",
                    requestBody.get().path("thinking").path("type").asText()
            );
            assertEquals("high", requestBody.get().path("reasoning_effort").asText());
            assertEquals(
                    "json_object",
                    requestBody.get().path("response_format").path("type").asText()
            );
        } finally {
            server.stop(0);
        }
    }

    @Test
    void retriesDeepSeekEmptyJsonContentWithoutRepeatingTools() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            int request = requests.incrementAndGet();
            String body = request == 1
                    ? """
                      {"choices":[{"message":{"role":"assistant","content":""}}],
                       "usage":{"prompt_tokens":10,"completion_tokens":0}}
                      """
                    : """
                      {"choices":[{"message":{"role":"assistant",
                       "content":"{\\"toolCalls\\":[],\\"finalAnswer\\":\\"recovered\\"}"}}],
                       "usage":{"prompt_tokens":10,"completion_tokens":8}}
                      """;
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            URI endpoint = URI.create(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions"
            );
            OpenAiCompatibleClient client = new OpenAiCompatibleClient(
                    HttpClient.newHttpClient(),
                    objectMapper,
                    endpoint,
                    "deepseek-v4-pro",
                    "deepseek-key",
                    Duration.ofSeconds(5),
                    8192,
                    true
            );

            ModelResponse response = client.chat(List.of(ChatMessage.user("return json")));

            assertEquals(2, requests.get());
            assertEquals(
                    "{\"toolCalls\":[],\"finalAnswer\":\"recovered\"}",
                    response.content()
            );
        } finally {
            server.stop(0);
        }
    }

    @Test
    void fallsBackToJsonObjectWhenProviderRejectsJsonSchemaAndCachesDecision() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        List<String> formats = new java.util.concurrent.CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            JsonNode request = objectMapper.readTree(exchange.getRequestBody());
            String format = request.path("response_format").path("type").asText();
            formats.add(format);
            requests.incrementAndGet();
            String body;
            int status;
            if (format.equals("json_schema")) {
                status = 400;
                body = "{\"error\":{\"message\":\"This response_format type is unavailable now\"}}";
            } else {
                status = 200;
                body = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"{\\\"thought\\\":\\\"\\\",\\\"toolCalls\\\":[],\\\"finalAnswer\\\":\\\"ok\\\"}\"}}]}";
            }
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            URI endpoint = URI.create(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions"
            );
            OpenAiCompatibleClient client = new OpenAiCompatibleClient(
                    HttpClient.newHttpClient(), objectMapper, endpoint, "model", "key",
                    Duration.ofSeconds(5), 512
            );

            ModelResponse first = client.chat(List.of(ChatMessage.user("one")));
            ModelResponse second = client.chat(List.of(ChatMessage.user("two")));
            assertEquals("ok", objectMapper.readTree(first.content()).path("finalAnswer").asText());
            assertEquals("ok", objectMapper.readTree(second.content()).path("finalAnswer").asText());
            assertEquals("json_object", first.responseFormat());
            assertEquals("json_object", second.responseFormat());
            assertEquals(2, first.requestAttempts());
            assertEquals("json_schema", first.fallbackFrom());
            assertEquals(1, second.requestAttempts());

            assertEquals(3, requests.get());
            assertEquals(List.of("json_schema", "json_object", "json_object"), formats);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void reportsBothFailuresWhenJsonObjectFallbackIsRejected() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            JsonNode request = objectMapper.readTree(exchange.getRequestBody());
            String format = request.path("response_format").path("type").asText();
            requests.incrementAndGet();
            String body = format.equals("json_schema")
                    ? "{\"error\":\"json_schema is not supported\"}"
                    : "{\"error\":\"prompt must contain json\"}";
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            URI endpoint = URI.create(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions"
            );
            OpenAiCompatibleClient client = new OpenAiCompatibleClient(
                    HttpClient.newHttpClient(), objectMapper, endpoint, "model", "key",
                    Duration.ofSeconds(5), 512
            );

            java.io.IOException error = assertThrows(
                    java.io.IOException.class,
                    () -> client.chat(List.of(ChatMessage.user("test")))
            );

            assertEquals(2, requests.get());
            assertTrue(error.getMessage().contains("Provider rejected json_schema"));
            assertTrue(error.getMessage().contains("json_object fallback returned HTTP 400"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void doesNotFallbackForUnrelatedResponseFormatValidationError() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            requests.incrementAndGet();
            byte[] response = "{\"error\":\"response_format request is malformed\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            URI endpoint = URI.create(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions"
            );
            OpenAiCompatibleClient client = new OpenAiCompatibleClient(
                    HttpClient.newHttpClient(), objectMapper, endpoint, "model", "key",
                    Duration.ofSeconds(5), 512
            );

            java.io.IOException error = assertThrows(
                    java.io.IOException.class,
                    () -> client.chat(List.of(ChatMessage.user("test")))
            );

            assertEquals(1, requests.get());
            assertTrue(error.getMessage().startsWith("Provider returned HTTP 400"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void plainTextRequestOmitsResponseFormat() throws Exception {
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            requestBody.set(objectMapper.readTree(exchange.getRequestBody()));
            byte[] response = "{\"choices\":[{\"message\":{\"content\":\"summary\"}}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            OpenAiCompatibleClient client = OpenAiCompatibleClient.deepSeek(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    "deepseek", "key", Duration.ofSeconds(5), 512
            );
            ModelResponse response = client.chatText(List.of(ChatMessage.user("summarize")));
            assertEquals("summary", response.content());
            assertEquals("text", response.responseFormat());
            assertEquals(false, requestBody.get().has("response_format"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void deepSeekRecoversFromEmptyStructuredResponsesWithPlainJsonRequest() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        List<Boolean> hasResponseFormat = new java.util.concurrent.CopyOnWriteArrayList<>();
        AtomicReference<String> recoveryPrompt = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            JsonNode request = objectMapper.readTree(exchange.getRequestBody());
            hasResponseFormat.add(request.has("response_format"));
            int requestNumber = requests.incrementAndGet();
            if (requestNumber == 3) {
                JsonNode requestMessages = request.path("messages");
                recoveryPrompt.set(requestMessages.get(requestMessages.size() - 1)
                        .path("content").asText());
            }
            String content = requestNumber < 3 ? "" :
                    "{\"thought\":\"recovered\",\"toolCalls\":[],\"finalAnswer\":\"ok\"}";
            byte[] response = ("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":"
                    + "{\"content\":" + objectMapper.writeValueAsString(content) + "}}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            OpenAiCompatibleClient client = OpenAiCompatibleClient.deepSeek(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    "deepseek", "key", Duration.ofSeconds(5), 512
            );

            ModelResponse response = client.chat(List.of(ChatMessage.user("test")));

            assertEquals("text", response.responseFormat());
            assertEquals(3, response.requestAttempts());
            assertEquals(List.of(true, true, false), hasResponseFormat);
            assertTrue(recoveryPrompt.get().contains("compact valid JSON object"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void deepSeekFallsBackToPlainJsonWhenAllResponseFormatsAreUnavailable() throws Exception {
        List<Boolean> formats = new java.util.concurrent.CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            JsonNode request = objectMapper.readTree(exchange.getRequestBody());
            boolean formatted = request.has("response_format");
            formats.add(formatted);
            String body = formatted
                    ? "{\"error\":{\"message\":\"This response_format type is unavailable now\"}}"
                    : "{\"choices\":[{\"message\":{\"content\":\"{\\\"thought\\\":\\\"ok\\\",\\\"toolCalls\\\":[],\\\"finalAnswer\\\":\\\"done\\\"}\"}}]}";
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(formatted ? 400 : 200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            OpenAiCompatibleClient client = OpenAiCompatibleClient.deepSeek(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    "deepseek", "key", Duration.ofSeconds(5), 512
            );
            ModelResponse response = client.chat(List.of(ChatMessage.user("test")));
            assertEquals("text", response.responseFormat());
            assertEquals(List.of(true, false), formats);
        } finally {
            server.stop(0);
        }
    }
}
