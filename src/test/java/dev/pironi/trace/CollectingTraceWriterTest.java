package dev.pironi.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.pironi.tool.ToolResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CollectingTraceWriterTest {

    @Test
    void recordsToolResultsAndReturnsSnapshot() {
        var writer = new CollectingTraceWriter();
        ObjectMapper mapper = new ObjectMapper();
        writer.toolResult(1, "http_get", mapper.createObjectNode().put("url", "x"),
                ToolResult.success("ok 200"));
        writer.completed(2, "done");

        var lines = writer.lines();
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("http_get ok"));
        assertTrue(lines.get(1).contains("completed done"));
    }

    @Test
    void modelResponseIsNotRecorded() {
        var writer = new CollectingTraceWriter();
        writer.modelResponse(1, new dev.pironi.model.ModelResponse("ignored", 0, 0, 0));
        assertTrue(writer.lines().isEmpty(), "model responses are large and not collected");
    }

    @Test
    void boundsLinesToFiftyWithTruncation() {
        var writer = new CollectingTraceWriter();
        for (int i = 0; i < 60; i++) {
            writer.completed(i, "iteration " + i);
        }
        assertEquals(50, writer.lines().size(), "bounded at 50 lines");
        // Oldest lines are dropped first.
        assertTrue(writer.lines().getFirst().contains("iteration 10"));
    }

    @Test
    void truncatesLongLines() {
        var writer = new CollectingTraceWriter();
        String longOutput = "x".repeat(500);
        writer.completed(1, longOutput);
        assertEquals(200, writer.lines().getFirst().length(), "each line truncated to 200 chars");
    }

    @Test
    void snapshotIsImmutable() {
        var writer = new CollectingTraceWriter();
        writer.completed(1, "done");
        var snapshot = writer.lines();
        try {
            snapshot.clear();
        } catch (UnsupportedOperationException expected) {
            assertTrue(true, "immutable copy");
            return;
        }
        assertEquals(1, writer.lines().size(), "original untouched");
    }
}
