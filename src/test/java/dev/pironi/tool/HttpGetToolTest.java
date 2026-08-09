package dev.pironi.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpGetToolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void fetchesBoundedPublicHttpsContent() {
        HttpGetTool tool = new HttpGetTool((uri, timeout) ->
                new HttpGetTool.FetchResponse(200, "forecast".getBytes(StandardCharsets.UTF_8))
        );

        ToolResult result = tool.execute(mapper.createObjectNode()
                .put("url", "https://8.8.8.8/weather").put("timeoutSeconds", 5));

        assertTrue(result.success());
        assertTrue(result.output().contains("HTTP 200"));
        assertTrue(result.output().contains("forecast"));
    }

    @Test void rejectsNonHttpsCredentialsAndPrivateDestinationsBeforeFetching() {
        HttpGetTool tool = new HttpGetTool((uri, timeout) -> {
            throw new AssertionError("fetch must not run");
        });

        assertFailureContains(tool, "http://8.8.8.8", "Only HTTPS");
        assertFailureContains(tool, "https://user:pass@8.8.8.8", "credentials");
        assertFailureContains(tool, "https://127.0.0.1", "private network");
        assertFailureContains(tool, "https://192.168.1.10", "private network");
        assertFailureContains(tool, "https://[::1]", "private network");
    }

    @Test void rejectsRedirectsErrorsAndOversizedBodies() {
        HttpGetTool redirect = new HttpGetTool((uri, timeout) ->
                new HttpGetTool.FetchResponse(302, new byte[0], "https://example.test/final"));
        ToolResult redirected = redirect.execute(mapper.createObjectNode()
                .put("url", "https://8.8.8.8"));
        assertFalse(redirected.success());
        assertTrue(redirected.output().contains("HTTP 302"));
        assertTrue(redirected.output().contains("redirect blocked"));
        assertTrue(redirected.output().contains("Location: https://example.test/final"));

        HttpGetTool huge = new HttpGetTool((uri, timeout) ->
                new HttpGetTool.FetchResponse(200, new byte[HttpGetTool.MAX_BODY_BYTES + 1]));
        ToolResult oversized = huge.execute(mapper.createObjectNode()
                .put("url", "https://8.8.8.8"));
        assertFalse(oversized.success());
        assertTrue(oversized.output().contains("exceeds"));
    }

    @Test void truncatesHtmlBeforeItBloatsTheAgentPrompt() {
        byte[] html = ("<html>" + "x".repeat(20_000)).getBytes(StandardCharsets.UTF_8);
        HttpGetTool tool = new HttpGetTool((uri, timeout) ->
                new HttpGetTool.FetchResponse(200, html, "", "text/html; charset=utf-8"));

        ToolResult result = tool.execute(mapper.createObjectNode().put("url", "https://8.8.8.8"));

        assertTrue(result.success());
        assertTrue(result.output().contains("HTML excerpt truncated"));
        assertTrue(result.output().length() < 9_000);
    }

    private void assertFailureContains(HttpGetTool tool, String url, String expected) {
        ToolResult result = tool.execute(mapper.createObjectNode().put("url", url));
        assertFalse(result.success());
        assertTrue(result.output().contains(expected), result.output());
    }
}
