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
                      "choices":[{"message":{"role":"assistant","content":"{\\"finalAnswer\\":\\"ok\\"}"}}],
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
            assertEquals("Bearer test-key", authorization.get());
            assertEquals("test-model", requestBody.get().path("model").asText());
            assertEquals(123, requestBody.get().path("max_tokens").asInt());
            assertEquals("json_object", requestBody.get().path("response_format").path("type").asText());
            assertTrue(requestBody.get().path("messages").isArray());
        } finally {
            server.stop(0);
        }
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
}
