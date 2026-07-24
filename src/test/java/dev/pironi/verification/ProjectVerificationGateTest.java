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
    void autoDetectsMavenAndDoesNotRunBeforeChange() throws Exception {
        Files.writeString(workspaceRoot.resolve("pom.xml"), "<project/>");
        ProjectVerificationGate gate = new ProjectVerificationGate(
                new Workspace(workspaceRoot),
                null,
                Duration.ofSeconds(2)
        );

        assertEquals("mvn test", gate.command());
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
}
