package dev.pironi.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolActivityFormatterTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void describesFileOperationsWithoutContent() {
        var arguments = mapper.createObjectNode()
                .put("path", "src/main/App.java")
                .put("content", "API_KEY=do-not-print");

        String line = ToolActivityFormatter.started("write_file", arguments);

        assertEquals("Writing src/main/App.java", line);
        assertFalse(line.contains("do-not-print"));
    }

    @Test void stripsUrlCredentialsQueryAndFragment() {
        var arguments = mapper.createObjectNode()
                .put("url", "https://user:password@example.com/weather?q=secret#token");

        String line = ToolActivityFormatter.started("http_get", arguments);

        assertEquals("Fetching https://example.com/weather", line);
        assertFalse(line.contains("secret"));
        assertFalse(line.contains("password"));
    }

    @Test void curlShowsOnlyExecutableAndSafeUrl() {
        var arguments = mapper.createObjectNode().put(
                "command",
                "curl -H 'Authorization: Bearer hidden' https://example.com/data?key=hidden"
        );

        String line = ToolActivityFormatter.started("run_command", arguments);

        assertEquals("Running curl https://example.com/data", line);
        assertFalse(line.contains("hidden"));
        assertFalse(line.contains("Authorization"));
    }

    @Test void genericCommandDoesNotExposeArguments() {
        var arguments = mapper.createObjectNode().put(
                "command", "python report.py --password hidden"
        );

        assertEquals(
                "Running command python",
                ToolActivityFormatter.started("run_command", arguments)
        );
        assertTrue(ToolActivityFormatter.finished("run_command", true, 12)
                .contains("Completed run_command in 12 ms"));
    }
}
