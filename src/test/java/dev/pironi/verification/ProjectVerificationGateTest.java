package dev.pironi.verification;

import dev.pironi.safety.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.assertNull;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectVerificationGateTest {
    @TempDir
    Path workspaceRoot;

    @Test
    void takesTheConfiguredCommandAndDoesNotRunBeforeChange() throws Exception {
        ProjectVerificationGate gate = new ProjectVerificationGate(
                new Workspace(workspaceRoot),
                "mvn -q test",
                Duration.ofSeconds(2)
        );

        assertEquals("mvn -q test", gate.command());
        assertFalse(gate.required());
        assertFalse(gate.verifyIfRequired().attempted());
    }

    @Test
    void failedVerificationRemainsRequiredUntilItPasses() throws Exception {
        ProjectVerificationGate gate = new ProjectVerificationGate(
                new Workspace(workspaceRoot),
                "test -f verified.marker",
                Duration.ofSeconds(2)
        );
        gate.markChanged();
        assertTrue(gate.required());

        VerificationResult failed = gate.verifyIfRequired();
        assertTrue(failed.attempted());
        assertFalse(failed.success());

        Files.writeString(workspaceRoot.resolve("verified.marker"), "");
        assertTrue(gate.verifyIfRequired().success());
        assertFalse(gate.required());
        assertFalse(gate.verifyIfRequired().attempted());
    }

    @Test
    void staysSilentWhenNoVerificationCommandWasConfigured() throws Exception {
        // A pom in the workspace used to be enough to run the whole test suite after any
        // mutation, including ones a test cannot judge, such as deleting a temp file.
        Files.writeString(workspaceRoot.resolve("pom.xml"), "<project/>");
        Files.writeString(workspaceRoot.resolve("mvnw"), "");
        ProjectVerificationGate gate = new ProjectVerificationGate(
                new Workspace(workspaceRoot), "", Duration.ofSeconds(5));

        gate.markChanged();

        assertEquals("", gate.command());
        assertFalse(gate.required());
        assertFalse(gate.verifyIfRequired().attempted());
    }

    @Test
    void runsTheProjectsTestsOnlyAfterACodeFileOfItsBuildChanged() throws Exception {
        Files.writeString(workspaceRoot.resolve("pom.xml"), "<project/>");
        ProjectVerificationGate gate = new ProjectVerificationGate(
                new Workspace(workspaceRoot), "", Duration.ofSeconds(5));

        gate.markChanged(List.of("notes/todo.md"));
        gate.markChanged(List.of("tmp/scratch.txt"));
        assertFalse(gate.required(), "a note or a temp file is nothing a test can judge");

        gate.markChanged(List.of("src/main/java/App.java"));
        assertTrue(gate.required());
        assertEquals("mvn", gate.detect().command().split(" ")[0]);
    }

    @Test
    void findsPytestAndNpmByTheirOwnFiles() throws Exception {
        Files.writeString(workspaceRoot.resolve("pyproject.toml"), "[tool.pytest.ini_options]\n");
        ProjectVerificationGate gate = new ProjectVerificationGate(
                new Workspace(workspaceRoot), "", Duration.ofSeconds(5));
        assertEquals("python3 -m pytest -q", gate.detect().command());
        gate.markChanged(List.of("app/main.js"));
        assertFalse(gate.required());
        gate.markChanged(List.of("app/main.py"));
        assertTrue(gate.required());

        Files.delete(workspaceRoot.resolve("pyproject.toml"));
        Files.writeString(workspaceRoot.resolve("package.json"), "{\"scripts\": {\"build\": \"x\", \"test\": \"jest\"}}");
        assertEquals("npm test --silent", gate.detect().command());
        Files.writeString(workspaceRoot.resolve("package.json"), "{\"scripts\": {\"build\": \"x\"}}");
        assertNull(gate.detect());
    }
}
