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

    @Test void parsesWindowsTasklistCsvWithSpacesCommasAndEscapedQuotes() {
        List<String> fields = ProcessInspectTool.NativeBackend.csvFields(
                "\"Example \"\"Worker\"\".exe\",\"6048\",\"Console\",\"1\",\"293,248 K\"");
        assertTrue(fields.size() == 5);
        assertTrue(fields.get(0).equals("Example \"Worker\".exe"));
        assertTrue(fields.get(1).equals("6048"));
        assertTrue(fields.get(4).equals("293,248 K"));
    }

    @Test void exactPidLookupDoesNotDependOnTopNAndSamplesCpu() {
        java.util.concurrent.atomic.AtomicReference<String> backendSort = new java.util.concurrent.atomic.AtomicReference<>();
        ProcessInspectTool tool = new ProcessInspectTool(sort -> {
            backendSort.set(sort);
            return List.of(
                    new ProcessInspectTool.Snapshot(12, "other", 999, 80, 4, 2, ""),
                    new ProcessInspectTool.Snapshot(836, "powershell.exe", 10, 97, 3, 2, "true"));
        });
        ToolResult result = tool.execute(mapper.createObjectNode().put("sortBy", "pid")
                .put("limit", 1).put("pid", 836));
        assertTrue(result.success());
        assertTrue(result.output().contains("pid=836"));
        assertFalse(result.output().contains("pid=12"));
        assertTrue(backendSort.get().equals("cpu"));
    }

    @Test void exactPidDefaultsToPidSortWithoutRepairTurn() {
        ProcessInspectTool tool = new ProcessInspectTool(sort -> List.of(
                new ProcessInspectTool.Snapshot(836, "worker", 1, 50, 2, 1, "")));
        ToolResult result = tool.execute(mapper.createObjectNode().put("pid", 836).put("limit", 1));
        assertTrue(result.success());
        assertTrue(result.output().startsWith("sort=pid; matchedProcesses=1"));
    }

    @Test void exactNameLookupAndInvalidFiltersAreBounded() {
        ProcessInspectTool tool = new ProcessInspectTool(sort -> List.of(
                new ProcessInspectTool.Snapshot(1, "Worker.EXE", 1, 1, 1, 1, ""),
                new ProcessInspectTool.Snapshot(2, "other.exe", 1, 1, 1, 1, "")));
        ToolResult exact = tool.execute(mapper.createObjectNode().put("sortBy", "memory")
                .put("limit", 10).put("name", "worker.exe"));
        assertTrue(exact.output().contains("pid=1"));
        assertFalse(exact.output().contains("pid=2"));
        assertFalse(tool.execute(mapper.createObjectNode().put("sortBy", "pid")
                .put("limit", 1).put("pid", -1)).success());
        assertFalse(tool.execute(mapper.createObjectNode().put("sortBy", "pid")
                .put("limit", 1).put("name", "..\\secret.exe")).success());
    }
}
