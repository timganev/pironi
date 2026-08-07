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
    void reportsCommandFailureOnEveryPlatformAndPipefailOnUnix() throws Exception {
        RunCommandTool tool = new RunCommandTool(
                new Workspace(workspaceRoot),
                Duration.ofSeconds(2),
                1_000
        );

        boolean windows = isWindows();
        String command = windows ? "exit /b 7" : "false | tail -1";
        ToolResult result = tool.execute(
                new ObjectMapper().createObjectNode().put("command", command)
        );

        assertFalse(result.success());
        assertTrue(result.output().startsWith(windows ? "exitCode=7" : "exitCode=1"));
        assertFalse(tool.requiresVerification());
    }

    @Test
    void commandUsesTheSameJavaRuntimeAsPironi() throws Exception {
        RunCommandTool tool = new RunCommandTool(
                new Workspace(workspaceRoot),
                Duration.ofSeconds(2),
                2_000
        );

        boolean windows = isWindows();
        String command = windows
                ? "echo JAVA_HOME=%JAVA_HOME% & where java"
                : "printf 'JAVA_HOME=%s\\n' \"$JAVA_HOME\"; command -v java";
        ToolResult result = tool.execute(
                new ObjectMapper().createObjectNode().put("command", command)
        );

        String javaHome = System.getProperty("java.home");
        String executable = windows ? "java.exe" : "java";
        assertTrue(result.success());
        assertTrue(result.output().contains("JAVA_HOME=" + javaHome));
        assertTrue(result.output().toLowerCase().contains(
                Path.of(javaHome, "bin", executable).toString().toLowerCase()
        ));
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
                .contains("win");
    }
}
