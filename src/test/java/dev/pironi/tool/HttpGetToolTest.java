package dev.pironi.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpGetToolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void fetchesBoundedPublicHttpsContent() {
        HttpGetTool tool = new HttpGetTool((uri, timeout, headers) ->
                new HttpGetTool.FetchResponse(200, "forecast".getBytes(StandardCharsets.UTF_8))
        );

        ToolResult result = tool.execute(mapper.createObjectNode()
                .put("url", "https://8.8.8.8/weather").put("timeoutSeconds", 5));

        assertTrue(result.success());
        assertTrue(result.output().contains("HTTP 200"));
        assertTrue(result.output().contains("forecast"));
    }

    @Test void rejectsNonHttpsCredentialsAndPrivateDestinationsBeforeFetching() {
        HttpGetTool tool = new HttpGetTool((uri, timeout, headers) -> {
            throw new AssertionError("fetch must not run");
        });

        assertFailureContains(tool, "http://8.8.8.8", "Only HTTPS");
        assertFailureContains(tool, "https://user:pass@8.8.8.8", "credentials");
        assertFailureContains(tool, "https://127.0.0.1", "private network");
        assertFailureContains(tool, "https://192.168.1.10", "private network");
        assertFailureContains(tool, "https://[::1]", "private network");
    }

    @Test void rejectsRedirectsErrorsAndOversizedBodies() {
        HttpGetTool redirect = new HttpGetTool((uri, timeout, headers) ->
                new HttpGetTool.FetchResponse(302, new byte[0], "https://example.test/final"));
        ToolResult redirected = redirect.execute(mapper.createObjectNode()
                .put("url", "https://8.8.8.8"));
        assertFalse(redirected.success());
        assertTrue(redirected.output().contains("HTTP 302"));
        assertTrue(redirected.output().contains("redirect blocked"));
        assertTrue(redirected.output().contains("Location: https://example.test/final"));

        HttpGetTool huge = new HttpGetTool((uri, timeout, headers) ->
                new HttpGetTool.FetchResponse(200, new byte[HttpGetTool.MAX_BODY_BYTES + 1]));
        ToolResult oversized = huge.execute(mapper.createObjectNode()
                .put("url", "https://8.8.8.8"));
        assertFalse(oversized.success());
        assertTrue(oversized.output().contains("exceeds"));
    }

    @Test void truncatesHtmlBeforeItBloatsTheAgentPrompt() {
        byte[] html = ("<html>" + "x".repeat(20_000)).getBytes(StandardCharsets.UTF_8);
        HttpGetTool tool = new HttpGetTool((uri, timeout, headers) ->
                new HttpGetTool.FetchResponse(200, html, "", "text/html; charset=utf-8"));

        ToolResult result = tool.execute(mapper.createObjectNode().put("url", "https://8.8.8.8"));

        assertTrue(result.success());
        assertTrue(result.output().contains("HTML excerpt truncated"));
        assertTrue(result.output().length() < 9_000);
    }

    @Test void resolvesAuthorizationPlaceholderForAllowlistedHost() {
        HeaderResolver resolver = new HeaderResolver(
                Map.of("PIRONI_API_KEY", "sk-secret-123"),
                Set.of("api.deepseek.com")
        );
        HttpGetTool tool = new HttpGetTool(resolver, (uri, timeout, headers) -> {
            assertEquals("Bearer sk-secret-123", headers.get("Authorization"));
            return new HttpGetTool.FetchResponse(200,
                    "{\"total_balance\":\"20.97\"}".getBytes(StandardCharsets.UTF_8));
        });

        ObjectNode args = mapper.createObjectNode().put("url", "https://api.deepseek.com/user/balance");
        ObjectNode headers = args.putObject("headers");
        headers.put("Authorization", "Bearer PIRONI_API_KEY");

        ToolResult result = tool.execute(args);

        assertTrue(result.success(), result.output());
        assertTrue(result.output().contains("[1 headers applied]"));
        assertTrue(result.output().contains("20.97"));
        // The real key must never leak into the tool result.
        assertFalse(result.output().contains("sk-secret"));
    }

    @Test void rejectsAuthorizationPlaceholderOnNonAllowlistedHost() {
        HeaderResolver resolver = new HeaderResolver(
                Map.of("PIRONI_API_KEY", "sk-secret-123"),
                Set.of("api.deepseek.com")
        );
        HttpGetTool tool = new HttpGetTool(resolver, (uri, timeout, headers) -> {
            throw new AssertionError("fetch must not run for a rejected header");
        });

        ObjectNode args = mapper.createObjectNode().put("url", "https://example.com/private");
        ObjectNode headers = args.putObject("headers");
        headers.put("Authorization", "Bearer PIRONI_API_KEY");

        ToolResult result = tool.execute(args);

        assertFalse(result.success());
        assertTrue(result.output().contains("not permitted"));
    }

    @Test void rejectsHardcodedAuthorizationWithoutPlaceholder() {
        HeaderResolver resolver = new HeaderResolver(
                Map.of("PIRONI_API_KEY", "sk-secret-123"),
                Set.of("api.deepseek.com")
        );
        HttpGetTool tool = new HttpGetTool(resolver, (uri, timeout, headers) -> {
            throw new AssertionError("fetch must not run for a rejected header");
        });

        ObjectNode args = mapper.createObjectNode().put("url", "https://api.deepseek.com/user/balance");
        ObjectNode headers = args.putObject("headers");
        headers.put("Authorization", "Bearer sk-hardcoded-by-model");

        ToolResult result = tool.execute(args);

        assertFalse(result.success());
        assertTrue(result.output().contains("not permitted"));
    }

    @Test void passesThroughPlainNonSensitiveHeaders() {
        HttpGetTool tool = new HttpGetTool((uri, timeout, headers) -> {
            assertEquals("en", headers.get("Accept-Language"));
            return new HttpGetTool.FetchResponse(200, "ok".getBytes(StandardCharsets.UTF_8));
        });

        ObjectNode args = mapper.createObjectNode().put("url", "https://8.8.8.8");
        ObjectNode headers = args.putObject("headers");
        headers.put("Accept-Language", "en");

        ToolResult result = tool.execute(args);

        assertTrue(result.success(), result.output());
        assertTrue(result.output().contains("[1 headers applied]"));
    }

    @Test void rejectsAttemptToOverrideTransportHeaders() {
        HttpGetTool tool = new HttpGetTool((uri, timeout, headers) -> {
            throw new AssertionError("fetch must not run");
        });

        ObjectNode args = mapper.createObjectNode().put("url", "https://8.8.8.8");
        ObjectNode headers = args.putObject("headers");
        headers.put("Host", "evil.example");

        ToolResult result = tool.execute(args);

        assertFalse(result.success());
        assertTrue(result.output().contains("not allowed"));
    }

    @Test void rejectsHeadersOnPrivateDestinationsWithoutFetching() {
        HttpGetTool tool = new HttpGetTool((uri, timeout, headers) -> {
            throw new AssertionError("fetch must not run");
        });

        ObjectNode args = mapper.createObjectNode().put("url", "https://127.0.0.1");
        ObjectNode headers = args.putObject("headers");
        headers.put("Authorization", "Bearer PIRONI_API_KEY");

        ToolResult result = tool.execute(args);

        assertFalse(result.success());
        assertTrue(result.output().contains("private network"));
    }

    @Test void rejectsNonObjectHeadersArgument() {
        HttpGetTool tool = new HttpGetTool((uri, timeout, headers) -> {
            throw new AssertionError("fetch must not run");
        });
        ToolResult result = tool.execute(mapper.createObjectNode()
                .put("url", "https://8.8.8.8").put("headers", "not-an-object"));
        assertFalse(result.success());
        assertTrue(result.output().contains("must be an object"));
    }

    private void assertFailureContains(HttpGetTool tool, String url, String expected) {
        ToolResult result = tool.execute(mapper.createObjectNode().put("url", url));
        assertFalse(result.success());
        assertTrue(result.output().contains(expected), result.output());
    }
}
