package dev.pironi.safety;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void refusalNamesTheWorkspaceAndTheWayOut() throws Exception {
        // The message a session actually hit: the file had just been read through a granted
        // root, so "Absolute paths are not allowed" read as "no tool can ever touch it".
        Path root = Files.createDirectory(temporaryDirectory.resolve("project"));
        Path elsewhere = Files.createDirectory(temporaryDirectory.resolve("repo"));
        Files.writeString(elsewhere.resolve("weather.txt"), "27C");
        Workspace workspace = new Workspace(root);
        java.util.List<Path> readable = java.util.List.of(elsewhere.toRealPath());
        workspace.useReadableRoots(() -> readable);

        IOException refused = assertThrows(IOException.class, () ->
                workspace.resolveForWrite(elsewhere.resolve("weather.txt").toString()));

        assertTrue(refused.getMessage().contains(root.toRealPath().toString()),
                refused.getMessage());
        assertTrue(refused.getMessage().contains("readable through an allowed root"),
                refused.getMessage());
        assertTrue(refused.getMessage().contains("/workspace "), refused.getMessage());
        assertTrue(refused.getMessage().contains("weather.txt"), refused.getMessage());
    }

    @Test
    void refusalStaysQuietAboutReadAccessThatDoesNotExist() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("only"));
        Workspace workspace = new Workspace(root);

        IOException refused = assertThrows(IOException.class, () ->
                workspace.resolveForWrite("../outside.txt"));

        assertFalse(refused.getMessage().contains("readable through"), refused.getMessage());
        assertTrue(refused.getMessage().contains("Move the sandbox"), refused.getMessage());
    }

    @Test
    void switchingTheWorkspaceMovesEveryScopedResolution() throws Exception {
        Path first = Files.createDirectory(temporaryDirectory.resolve("first"));
        Path second = Files.createDirectory(temporaryDirectory.resolve("second"));
        Files.writeString(second.resolve("file.txt"), "there");
        Workspace workspace = new Workspace(first);

        assertThrows(IOException.class, () -> workspace.resolveExisting("file.txt"));
        assertEquals(second.toRealPath(), workspace.switchTo(second));
        assertEquals(second.toRealPath().resolve("file.txt"), workspace.resolveExisting("file.txt"));
    }

    @Test
    void switchingToSomethingThatIsNotADirectoryIsRefused() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("root"));
        Path file = Files.writeString(temporaryDirectory.resolve("plain.txt"), "x");
        Workspace workspace = new Workspace(root);

        assertThrows(RuntimeException.class, () -> workspace.switchTo(file));
        assertEquals(root.toRealPath(), workspace.root());
    }

    @Test
    void resolvesExistingPathInsideWorkspace() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("workspace"));
        Path file = Files.writeString(root.resolve("inside.txt"), "content");

        assertEquals(file.toRealPath(), new Workspace(root).resolveExisting("inside.txt"));
    }

    @Test
    void createsExplicitMissingWorkspaceDirectory() throws Exception {
        Path root = temporaryDirectory.resolve("Documents").resolve("project");

        Workspace workspace = new Workspace(root);

        assertEquals(root.toRealPath(), workspace.root());
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

    @Test
    void aTildePathIsRefusedAsOutsideRatherThanGluedOn() throws Exception {
        // It used to become <workspace>/~/..., a directory that has never existed, so the refusal
        // never came and the write landed somewhere nobody looked.
        Workspace workspace = new Workspace(temporaryDirectory);

        IOException failure = assertThrows(
                IOException.class, () -> workspace.resolveForWrite("~/notes.md"));

        assertTrue(failure.getMessage().contains("Absolute paths are not allowed"),
                failure.getMessage());
    }
}
