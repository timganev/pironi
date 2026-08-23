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
    void reportsCommandFailureOnEveryPlatform() throws Exception {
        RunCommandTool tool = new RunCommandTool(
                new Workspace(workspaceRoot),
                Duration.ofSeconds(2),
                1_000
        );

        boolean windows = isWindows();
        // The workspace lexical guard intentionally treats /b as an absolute-path token.
        // This cmd process is already isolated by /c, so plain `exit 7` is sufficient.
        String command = windows ? "exit 7" : "exit 7";
        ToolResult result = tool.execute(
                new ObjectMapper().createObjectNode().put("command", command)
        );

        assertFalse(result.success());
        assertTrue(result.output().startsWith("exitCode=7"));
        assertFalse(tool.requiresVerification());
    }

    @Test
    void samplingALargeOutputWithHeadIsNotAFailure() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(isWindows());
        RunCommandTool tool = new RunCommandTool(
                new Workspace(workspaceRoot),
                Duration.ofSeconds(10),
                4_000
        );

        // Under pipefail this exited 141, and inside a substitution the non-zero status
        // short-circuited the rest of the command line, so nothing after it ran at all.
        ToolResult result = tool.execute(new ObjectMapper().createObjectNode()
                .put("command", "f=$(seq 1 100000 | head -1) && echo \"got $f\""));

        assertTrue(result.success(), result.output());
        assertTrue(result.output().contains("got 1"), result.output());
    }

    @Test
    void aTimedOutCommandStillReportsWhatItPrinted() throws Exception {
        // A run lost five minutes to a command that timed out mid-way through 18,000 files and
        // came back saying only that it had timed out, leaving the model unable to tell whether
        // the approach was close or hopeless.
        org.junit.jupiter.api.Assumptions.assumeFalse(isWindows());
        RunCommandTool tool = new RunCommandTool(
                new Workspace(workspaceRoot), Duration.ofSeconds(1), 1_000);

        ToolResult result = tool.execute(new ObjectMapper().readTree("""
                {"command":"echo processed 12000 of 18646; sleep 30","timeoutSeconds":1}
                """));

        assertFalse(result.success());
        assertTrue(result.output().contains("timed out after 1 seconds"), result.output());
        assertTrue(result.output().contains("processed 12000 of 18646"), result.output());
    }

    @Test
    void aTimedOutCommandThatPrintedNothingSaysSo() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(isWindows());
        RunCommandTool tool = new RunCommandTool(
                new Workspace(workspaceRoot), Duration.ofSeconds(1), 1_000);

        ToolResult result = tool.execute(new ObjectMapper().readTree("""
                {"command":"sleep 30","timeoutSeconds":1}
                """));

        assertFalse(result.success());
        assertTrue(result.output().contains("printed nothing before it was stopped"), result.output());
    }

    @Test
    void anExitCodeThatIsAnAnswerSaysSo() throws Exception {
        // grep exits 1 when it matches nothing, having printed the count the caller asked for.
        org.junit.jupiter.api.Assumptions.assumeFalse(isWindows());
        RunCommandTool tool = new RunCommandTool(
                new Workspace(workspaceRoot), Duration.ofSeconds(5), 1_000);

        ToolResult result = tool.execute(new ObjectMapper().readTree("""
                {"command":"printf 'a\\n' > f.txt; grep -c zzz f.txt"}
                """));

        assertTrue(result.output().contains("exitCode=1"), result.output());
        assertTrue(result.output().contains("some programs report an answer"), result.output());
    }

    @Test
    void aCleanFailureIsNotExplainedAway() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(isWindows());
        RunCommandTool tool = new RunCommandTool(
                new Workspace(workspaceRoot), Duration.ofSeconds(5), 1_000);

        ToolResult result = tool.execute(new ObjectMapper().readTree("""
                {"command":"exit 3"}
                """));

        assertFalse(result.output().contains("some programs report an answer"), result.output());
    }

    @Test
    void signalNamesAreNotInventedForCmdExe() {
        // 128+N is a POSIX shell convention. On cmd.exe an exit code is whatever the program
        // chose, so calling 137 "out of memory" there would invent a cause that does not exist.
        assertTrue(RunCommandTool.cause(137, "/bin/bash").contains("out of memory"));
        assertEquals("", RunCommandTool.cause(137, "cmd.exe"));
        assertEquals("", RunCommandTool.cause(141, "cmd.exe"));
        // cmd has one convention of its own worth naming.
        assertTrue(RunCommandTool.cause(9009, "cmd.exe").contains("command not found"));
        assertEquals("", RunCommandTool.cause(9009, "/bin/bash"));
    }

    @Test
    void namesTheSignalBehindAnExitCode() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(isWindows());
        RunCommandTool tool = new RunCommandTool(
                new Workspace(workspaceRoot),
                Duration.ofSeconds(10),
                2_000
        );

        // "exitCode=137" on its own reads as an ordinary failure, so the same command gets
        // retried or a readable source is written off; the cause is what lets the model adapt.
        ToolResult killed = tool.execute(new ObjectMapper().createObjectNode()
                .put("command", "kill -9 $$"));
        assertFalse(killed.success());
        assertTrue(killed.output().contains("out of memory"), killed.output());

        ToolResult missing = tool.execute(new ObjectMapper().createObjectNode()
                .put("command", "definitely-not-a-real-command"));
        assertFalse(missing.success());
        assertTrue(missing.output().contains("command not found"), missing.output());

        ToolResult ordinary = tool.execute(new ObjectMapper().createObjectNode()
                .put("command", "exit 3"));
        assertTrue(ordinary.output().startsWith("exitCode=3\n"), ordinary.output());
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

    @Test
    void aCommandThatAnsweredThroughItsExitCodeIsNotAFailedToolCall() throws Exception {
        // Observed twice in one run: findstr and grep answered "nothing matched", the call came
        // back as failed, and the agent rewrote a script that had been working.
        org.junit.jupiter.api.Assumptions.assumeFalse(isWindows());
        RunCommandTool tool = new RunCommandTool(
                new Workspace(workspaceRoot), Duration.ofSeconds(5), 1_000);

        ToolResult result = tool.execute(new ObjectMapper().readTree("""
                {"command":"printf 'a\n' > f.txt; grep -c zzz f.txt"}
                """));

        assertTrue(result.success(), result.output());
        assertTrue(result.output().startsWith("exitCode=1"), result.output());
    }

    @Test
    void aSilentNonZeroExitIsStillAFailure() throws Exception {
        // Nothing printed means there is no answer to read, so calling it a success would say
        // the command did something when all that is known is that it stopped.
        RunCommandTool tool = new RunCommandTool(
                new Workspace(workspaceRoot), Duration.ofSeconds(5), 1_000);

        ToolResult result = tool.execute(
                new ObjectMapper().createObjectNode().put("command", "exit 4"));

        assertFalse(result.success(), result.output());
    }
}
