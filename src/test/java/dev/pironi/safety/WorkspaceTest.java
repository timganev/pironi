package dev.pironi.safety;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkspaceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void resolvesExistingPathInsideWorkspace() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("workspace"));
        Path file = Files.writeString(root.resolve("inside.txt"), "content");

        assertEquals(file.toRealPath(), new Workspace(root).resolveExisting("inside.txt"));
    }

    @Test
    void rejectsAbsoluteAndTraversalPaths() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("workspace"));
        Workspace workspace = new Workspace(root);

        assertThrows(IOException.class, () -> workspace.resolveForWrite("/tmp/outside.txt"));
        assertThrows(IOException.class, () -> workspace.resolveForWrite("../outside.txt"));
    }

    @Test
    void rejectsSymlinkEscapingWorkspace() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("workspace"));
        Path outside = Files.createDirectory(temporaryDirectory.resolve("outside"));
        Files.createSymbolicLink(root.resolve("escape"), outside);
        Workspace workspace = new Workspace(root);

        assertThrows(IOException.class, () -> workspace.resolveForWrite("escape/file.txt"));
    }
}
