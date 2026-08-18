package dev.pironi.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.pironi.safety.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunCommandToolTest {
    @TempDir
    Path workspaceRoot;

    @Test
    void descriptionNamesHowFarTheShellReaches() throws Exception {
        Workspace workspace = new Workspace(workspaceRoot);
        Duration timeout = Duration.ofSeconds(2);

        String workspaceScoped = new RunCommandTool(workspace, timeout, 1_000).description();
        assertTrue(workspaceScoped.contains("Paths outside the workspace are rejected"),
                workspaceScoped);

        String userScoped = new RunCommandTool(
                workspace, timeout, 1_000, ShellScope.USER
        ).description();
        // Without this the agent takes the search roots of list_files for the whole world and
        // never looks outside them, even though the shell can.
        assertTrue(userScoped.contains("outside the roots that list_files and find_files accept"),
                userScoped);

        String unrestricted = new RunCommandTool(
                workspace, timeout, 1_000, ShellScope.UNRESTRICTED
        ).description();
        assertTrue(unrestricted.contains("no restriction"), unrestricted);
    }

    @Test
    void reportsCommandFailureOnEveryPlatformAndPipefailOnUnix() throws Exception {
        RunCommandTool tool = new RunCommandTool(
                new Workspace(workspaceRoot),
                Duration.ofSeconds(2),
                1_000
        );

        boolean windows = isWindows();
        // The workspace lexical guard intentionally treats /b as an absolute-path token.
        // This cmd process is already isolated by /c, so plain `exit 7` is sufficient.
        String command = windows ? "exit 7" : "false | tail -1";
        ToolResult result = tool.execute(
                new ObjectMapper().createObjectNode().put("command", command)
        );

        assertFalse(result.success());
        assertTrue(result.output().startsWith(windows ? "exitCode=7" : "exitCode=1"));
        assertFalse(tool.requiresVerification());
    }

    @Test
    void commandUsesTheSameJavaRuntimeAsPironi() throws Exception {
        // Ten seconds, not two: on a loaded Windows CI runner starting cmd.exe and
        // resolving `where java` has taken longer than a two second budget.
        RunCommandTool tool = new RunCommandTool(
                new Workspace(workspaceRoot),
                Duration.ofSeconds(10),
                8_000
        );

        boolean windows = isWindows();
        String command = windows
                ? "echo JAVA_HOME=%JAVA_HOME%& where java"
                : "printf 'JAVA_HOME=%s\\n' \"$JAVA_HOME\"; command -v java";
        ToolResult result = tool.execute(
                new ObjectMapper().createObjectNode().put("command", command)
        );

        Path javaHome = Path.of(System.getProperty("java.home"));
        assertTrue(result.success(), () -> "command failed: " + result.output());

        // Compare canonical paths rather than raw strings. Windows runners report the
        // same directory as C:\Users\RUNNER~1\... or with a different drive-letter case,
        // so a substring match on the reported path is not a stable assertion.
        String reportedHome = result.output().lines()
                .filter(line -> line.startsWith("JAVA_HOME="))
                .map(line -> line.substring("JAVA_HOME=".length()).trim())
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no JAVA_HOME line in output: " + result.output()));
        assertEquals(canonical(javaHome), canonical(Path.of(reportedHome)),
                () -> "JAVA_HOME must point at the running runtime; output: " + result.output());

        Path resolvedJava = result.output().lines()
                .map(String::trim)
                .filter(line -> line.toLowerCase(java.util.Locale.ROOT)
                        .endsWith(windows ? "java.exe" : "/java"))
                .map(Path::of)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no java executable in output: " + result.output()));
        assertEquals(canonical(javaHome.resolve("bin")), canonical(resolvedJava.getParent()),
                () -> "java on PATH must come from the running runtime; output: " + result.output());
    }

    /** Real path when it exists, normalised absolute path otherwise; lowercased on Windows. */
    private static String canonical(Path path) {
        Path resolved;
        try {
            resolved = path.toRealPath();
        } catch (java.io.IOException e) {
            resolved = path.toAbsolutePath().normalize();
        }
        String text = resolved.toString();
        return isWindows() ? text.toLowerCase(java.util.Locale.ROOT) : text;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
                .contains("win");
    }
}
