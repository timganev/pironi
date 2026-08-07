package dev.pironi.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.pironi.model.ModelResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonlTraceWriterTest {
    @TempDir Path root;

    @Test
    void recordsProviderAttemptsFallbackAndErrors() throws Exception {
        Path trace = root.resolve("trace.jsonl");
        ObjectMapper mapper = new ObjectMapper();
        try (JsonlTraceWriter writer = new JsonlTraceWriter(trace, mapper)) {
            writer.modelResponse(2, new ModelResponse(
                    "{}", 1, 2, 3, 0, "stop", "json_object", 2,
                    "json_schema", "HTTP 400: unavailable"
            ));
            writer.modelError(3, "HTTP 400");
        }

        var lines = Files.readAllLines(trace);
        var response = mapper.readTree(lines.get(0));
        var error = mapper.readTree(lines.get(1));
        assertEquals(2, response.path("requestAttempts").asInt());
        assertEquals("json_schema", response.path("fallbackFrom").asText());
        assertEquals("HTTP 400: unavailable", response.path("fallbackReason").asText());
        assertEquals("model_error", error.path("type").asText());
        assertEquals("HTTP 400", error.path("error").asText());
    }
}
