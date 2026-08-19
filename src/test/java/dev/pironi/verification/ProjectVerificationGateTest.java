package dev.pironi.verification;

import dev.pironi.safety.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

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
}
