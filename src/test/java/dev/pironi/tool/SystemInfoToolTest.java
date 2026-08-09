package dev.pironi.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.pironi.safety.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemInfoToolTest {
    @TempDir Path root;
    @Test void reportsMeasuredPortableFacts() throws Exception {
        ToolResult result = new SystemInfoTool(new Workspace(root)).execute(new ObjectMapper().createObjectNode());
        assertTrue(result.success(), result.output());
        assertTrue(result.output().contains("logicalProcessors="));
        assertTrue(result.output().contains("physicalMemoryBytes="));
        assertTrue(result.output().contains("workspaceUsableBytes="));
        assertTrue(result.output().contains("java="));
    }
}
