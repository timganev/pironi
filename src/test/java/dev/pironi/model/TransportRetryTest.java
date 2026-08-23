package dev.pironi.model;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A rate limit is not an answer, and this client had no retry at all: one 429 from OpenRouter
 * ended the turn, while the same failure against Ollama was retried four times with backoff. The
 * asymmetry was invisible because the provider that retries is the one run locally, where rate
 * limits do not happen.
 */
class TransportRetryTest {
    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    private OpenAiCompatibleClient clientFor(String path) {
        server.start();
        return new OpenAiCompatibleClient(
                URI.create("http://" + server.getAddress().getHostString() + ":"
                        + server.getAddress().getPort() + path),
                "test-model", "test-key", Duration.ofSeconds(5), 256);
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static final String ANSWER = """
            {"choices":[{"finish_reason":"stop","message":{"content":"{\\"finalAnswer\\":\\"ok\\"}"}}]}
            """;

    @Test
    void aRateLimitIsRetriedRatherThanEndingTheTurn() throws Exception {
        server.createContext("/v1/chat/completions", exchange -> {
            if (requests.incrementAndGet() == 1) respond(exchange, 429, "{\"error\":\"slow down\"}");
            else respond(exchange, 200, ANSWER);
        });
        OpenAiCompatibleClient client = clientFor("/v1/chat/completions");

        ModelResponse response = client.chat(List.of(ChatMessage.user("hello")));

        assertEquals(2, requests.get(), "the 429 should have been sent again, not surfaced");
        assertTrue(response.content().contains("finalAnswer"), response.content());
    }

    @Test
    void aBadGatewayIsRetried() throws Exception {
        server.createContext("/v1/chat/completions", exchange -> {
            if (requests.incrementAndGet() <= 2) respond(exchange, 502, "upstream is down");
            else respond(exchange, 200, ANSWER);
        });
        OpenAiCompatibleClient client = clientFor("/v1/chat/completions");

        client.chat(List.of(ChatMessage.user("hello")));

        assertEquals(3, requests.get());
    }

    /**
     * A 400 is the provider objecting to the request itself, which is how the structured-output
     * fallback learns to stop asking for a schema. Repeating it would spend the same rejection
     * three more times and delay the fallback by the whole backoff.
     */
    @Test
    void aRejectedRequestIsNotSentAgain() throws Exception {
        server.createContext("/v1/chat/completions", exchange -> {
            requests.incrementAndGet();
            respond(exchange, 400, "{\"error\":\"model does not exist\"}");
        });
        OpenAiCompatibleClient client = clientFor("/v1/chat/completions");

        IOException failure = assertThrows(IOException.class,
                () -> client.chat(List.of(ChatMessage.user("hello"))));

        assertEquals(1, requests.get(), "a 400 was sent " + requests.get() + " times");
        assertTrue(failure.getMessage().contains("400"), failure.getMessage());
    }
}
