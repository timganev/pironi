package dev.pironi.model;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** --swarm: a 429 or 503 that says when to come back is waited out as asked, not guessed at. */
class RetryAfterTest {
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
        RateLimitSignals.honorRetryAfter(false);
    }

    @Test
    void secondsAndDatesAreBothRead() {
        assertEquals(2_000, OpenAiCompatibleClient.retryAfterMillis("2"));
        assertEquals(500, OpenAiCompatibleClient.retryAfterMillis("0.5"));
        assertEquals(-1, OpenAiCompatibleClient.retryAfterMillis(null));
        assertEquals(-1, OpenAiCompatibleClient.retryAfterMillis("soon"));
        String in3s = ZonedDateTime.now(ZoneOffset.UTC).plusSeconds(3).format(DateTimeFormatter.RFC_1123_DATE_TIME);
        long ms = OpenAiCompatibleClient.retryAfterMillis(in3s);
        assertTrue(ms > 1_000 && ms <= 3_000, "date read as " + ms + " ms");
    }

    @Test
    void underSwarmAShortRetryAfterIsUsedInsteadOfTheBackoff() throws Exception {
        RateLimitSignals.honorRetryAfter(true);
        long before = RateLimitSignals.count();
        long elapsed = timeOneRateLimitedCall("0");
        assertTrue(elapsed < 700, "waited " + elapsed + " ms although the server said 0 s");
        assertEquals(before + 1, RateLimitSignals.count(), "the 429 was not counted for the dispatcher");
    }

    @Test
    void withoutSwarmTheBackoffIsUnchanged() throws Exception {
        long elapsed = timeOneRateLimitedCall("0");
        assertTrue(elapsed >= 900, "backoff shortened without --swarm: " + elapsed + " ms");
    }

    private long timeOneRateLimitedCall(String retryAfter) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            if (requests.incrementAndGet() == 1) {
                exchange.getResponseHeaders().add("Retry-After", retryAfter);
                respond(exchange, 429, "{\"error\":\"busy\"}");
            } else {
                respond(exchange, 200, """
                        {"choices":[{"finish_reason":"stop","message":{"content":"{\\"finalAnswer\\":\\"ok\\"}"}}]}
                        """);
            }
        });
        server.start();
        OpenAiCompatibleClient client = new OpenAiCompatibleClient(
                URI.create("http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort()
                        + "/v1/chat/completions"), "m", "k", Duration.ofSeconds(5), 256);
        long t0 = System.nanoTime();
        client.chat(List.of(ChatMessage.user("hi")));
        assertEquals(2, requests.get());
        return (System.nanoTime() - t0) / 1_000_000;
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
