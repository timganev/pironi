package dev.pironi.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.pironi.safety.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunCommandToolTest {
    @TempDir
    Path workspaceRoot;

    @Test
    void pipelinePreservesFailureWithPipefail() throws Exception {
        RunCommandTool tool = new RunCommandTool(
                new Workspace(workspaceRoot),
                Duration.ofSeconds(2),
                1_000
        );

        ToolResult result = tool.execute(new ObjectMapper().readTree("""
                {"command":"false | tail -1"}
                """));

        assertFalse(result.success());
        assertTrue(result.output().startsWith("exitCode=1"));
        assertFalse(tool.requiresVerification());
    }

    @Test
    void commandUsesTheSameJavaRuntimeAsPironi() throws Exception {
        RunCommandTool tool = new RunCommandTool(
                new Workspace(workspaceRoot),
                Duration.ofSeconds(2),
                2_000
        );

        ToolResult result = tool.execute(new ObjectMapper().readTree("""
                {"command":"printf 'JAVA_HOME=%s\\n' \\"$JAVA_HOME\\"; command -v java"}
                """));

        String javaHome = System.getProperty("java.home");
        assertTrue(result.success());
        assertTrue(result.output().contains("JAVA_HOME=" + javaHome));
        assertTrue(result.output().contains(Path.of(javaHome, "bin", "java").toString()));
    }
}
