package dev.pironi.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessInspectToolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void ranksMemoryHogsWithoutExposingCommandLines() {
        ProcessInspectTool tool = new ProcessInspectTool(sort -> List.of(
                new ProcessInspectTool.Snapshot(10, "/usr/bin/small", 10 * 1048576L, 1, 50, 8, ""),
                new ProcessInspectTool.Snapshot(20, "/opt/Big Worker", 900 * 1048576L, 2, 2, 9, "true")
        ));
        ToolResult result = tool.execute(mapper.createObjectNode()
                .put("sortBy", "memory").put("limit", 1));
        assertTrue(result.success());
        assertTrue(result.output().contains("pid=20"));
        assertTrue(result.output().contains("900.0 MiB"));
        assertFalse(result.output().contains("pid=10"));
    }

    @Test void ranksCpuAndBoundsOutput() {
        ProcessInspectTool tool = new ProcessInspectTool(sort -> List.of(
                new ProcessInspectTool.Snapshot(1, "one", 1, 3, 100, 100, ""),
                new ProcessInspectTool.Snapshot(2, "two", 2, 9, 1, 10, "")
        ));
        ToolResult result = tool.execute(mapper.createObjectNode()
                .put("sortBy", "cpu").put("limit", 1));
        assertTrue(result.output().contains("pid=2"));
        assertFalse(result.output().contains("pid=1"));
    }

    @Test void rejectsUnknownSortAndOversizedLimit() {
        ProcessInspectTool tool = new ProcessInspectTool(sort -> List.of());
        assertFalse(tool.execute(mapper.createObjectNode().put("sortBy", "command-line")
                .put("limit", 10)).success());
        assertFalse(tool.execute(mapper.createObjectNode().put("sortBy", "memory")
                .put("limit", 1000)).success());
    }

    @Test void sanitizesProcessNamesAndRepresentsUnavailableMetricsHonestly() {
        ProcessInspectTool tool = new ProcessInspectTool(sort -> List.of(
                new ProcessInspectTool.Snapshot(7, "bad\nname --password=secret", -1, -1, -1, -1, "")
        ));
        String output = tool.execute(mapper.createObjectNode()
                .put("sortBy", "pid").put("limit", 5)).output();
        assertFalse(output.contains("--password"));
        assertTrue(output.contains("memory=unknown"));
        assertTrue(output.contains("cpuNow=unknown"));
        assertTrue(output.contains("cpuTotal=unknown"));
    }
}
