package dev.pironi.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Regression test: the client must not attempt an h2c upgrade on cleartext endpoints.
 *
 * <p>The JDK HTTP client defaults to HTTP/2 and, because ALPN is unavailable over plain HTTP,
 * advertises an upgrade with {@code Upgrade: h2c} and {@code HTTP2-Settings}. Uvicorn, which serves
 * vLLM's OpenAI-compatible API, declines the upgrade but drops the request body while doing so, and
 * vLLM then fails the call with {@code HTTP 400 ... 'loc': ('body',), 'msg': 'Field required'}.
 * Reproduced against vLLM 0.21.1 on 2026-08-18: a client pinned to HTTP/1.1 got 200 where the
 * default client got 400.
 */
class OpenAiCompatibleClientHttpVersionTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void doesNotAdvertiseH2cUpgradeAndSendsBody() throws Exception {
        AtomicReference<String> upgradeHeader = new AtomicReference<>();
        AtomicReference<String> http2SettingsHeader = new AtomicReference<>();
        AtomicReference<String> receivedBody = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            upgradeHeader.set(exchange.getRequestHeaders().getFirst("Upgrade"));
            http2SettingsHeader.set(exchange.getRequestHeaders().getFirst("HTTP2-Settings"));
            receivedBody.set(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                    {"choices":[{"message":{"role":"assistant","content":"{\\"finalAnswer\\":\\"ok\\"}"}}],\
                    "usage":{"prompt_tokens":5,"completion_tokens":3}}\
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            OpenAiCompatibleClient client = new OpenAiCompatibleClient(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"),
                    "test-model",
                    "test-key",
                    Duration.ofSeconds(5),
                    512
            );

            client.chat(List.of(ChatMessage.user("hello")));
        } finally {
            server.stop(0);
        }

        assertNull(upgradeHeader.get(), "must not advertise an h2c upgrade on cleartext endpoints");
        assertNull(http2SettingsHeader.get(), "must not send HTTP2-Settings on cleartext endpoints");
        assertEquals(
                "test-model",
                objectMapper.readTree(receivedBody.get()).path("model").asText(),
                "request body must reach the server intact"
        );
    }
}
