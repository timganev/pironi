package dev.pironi.safety;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CheckpointManagerTest {
    @TempDir
    Path temporaryDirectory;

    /** Closed explicitly: on Windows an open directory stream blocks the temp-dir cleanup. */
    private static long countIn(Path directory) throws java.io.IOException {
        try (var entries = Files.list(directory)) {
            return entries.count();
        }
    }

    @Test
    void rollsBackToWhereTheFileWasEvenAfterTheWorkspaceMoved() throws Exception {
        // The checkpoint used to keep only a workspace-relative path, so a rollback taken
        // before /workspace would have restored the file into the new sandbox instead - a
        // stray copy in one project and the original still missing in the other.
        Path first = Files.createDirectory(temporaryDirectory.resolve("first"));
        Path second = Files.createDirectory(temporaryDirectory.resolve("second"));
        Workspace workspace = new Workspace(first);
        CheckpointManager checkpoints = new CheckpointManager(workspace);

        Path file = Files.writeString(first.resolve("notes.txt"), "original\n");
        checkpoints.create(file);
        Files.delete(file);
        workspace.switchTo(second);

        checkpoints.rollbackLatest();

        assertEquals("original\n", Files.readString(file));
        assertFalse(Files.exists(second.resolve("notes.txt")));
    }

    @Test
    void closingASessionRemovesCopiesNothingCanRollBackAnyMore() throws Exception {
        // The rollback stack lives in memory and dies with the process, so every checkpoint left
        // behind is an unusable full copy of a file - including files the agent was asked to
        // delete, whose contents then outlive the deletion indefinitely.
        Path root = Files.createDirectory(temporaryDirectory.resolve("project"));
        Workspace workspace = new Workspace(root);
        CheckpointManager checkpoints = new CheckpointManager(workspace);
        Path secret = Files.writeString(root.resolve("secret.txt"), "content\n");
        checkpoints.create(secret);
        Path store = root.resolve(".pironi/checkpoints");

        assertEquals(1, countIn(store));
        assertEquals(1, checkpoints.discardAll());

        assertEquals(0, countIn(store));
        assertTrue(Files.exists(secret), "the file itself is untouched");
    }

    @Test
    void prunesOnlyTheCopiesLeftBehindLongEnoughToBeOrphans() throws Exception {
        // Age is the only safe test: another Pironi may be running in this workspace right now,
        // and its checkpoints are minutes old.
        Path root = Files.createDirectory(temporaryDirectory.resolve("aging"));
        Workspace workspace = new Workspace(root);
        CheckpointManager checkpoints = new CheckpointManager(workspace);
        Path file = Files.writeString(root.resolve("file.txt"), "x");
        var fresh = checkpoints.create(file);
        var old = checkpoints.create(file);
        Files.setLastModifiedTime(old.directory(), java.nio.file.attribute.FileTime.fromMillis(
                System.currentTimeMillis() - java.time.Duration.ofDays(30).toMillis()));

        assertEquals(1, checkpoints.pruneOrphans(java.time.Duration.ofDays(7)));

        assertTrue(Files.exists(fresh.directory()), "a recent checkpoint may still be in use");
        assertFalse(Files.exists(old.directory()));
    }

    @Test
    void newCheckpointsFollowTheCurrentWorkspace() throws Exception {
        Path first = Files.createDirectory(temporaryDirectory.resolve("one"));
        Path second = Files.createDirectory(temporaryDirectory.resolve("two"));
        Workspace workspace = new Workspace(first);
        CheckpointManager checkpoints = new CheckpointManager(workspace);

        workspace.switchTo(second);
        Path file = Files.writeString(second.resolve("data.txt"), "x");
        var checkpoint = checkpoints.create(file);

        assertTrue(checkpoint.directory().startsWith(
                        second.toRealPath().resolve(".pironi/checkpoints")),
                checkpoint.directory().toString());
        assertEquals("data.txt", checkpoint.relativePath());
    }

    /**
     * Undo is a stack and this one had no bottom. Every mutation copies the file whole, so an
     * automatic session editing the same file over and over filled .pironi/checkpoints with
     * copies nobody would roll back to, bounded by nothing but the length of the run - the orphan
     * sweep only reaches sessions that have already ended.
     */
    @Test
    void theUndoStackHasABottom() throws Exception {
        Workspace workspace = new Workspace(temporaryDirectory);
        Path file = Files.writeString(temporaryDirectory.resolve("data.txt"), "0");
        CheckpointManager manager = new CheckpointManager(workspace);

        for (int edit = 1; edit <= CheckpointManager.MAX_CHECKPOINTS + 15; edit++) {
            manager.create(file);
            Files.writeString(file, String.valueOf(edit));
        }

        assertEquals(CheckpointManager.MAX_CHECKPOINTS,
                countIn(temporaryDirectory.resolve(".pironi/checkpoints")),
                "checkpoints on disk should stop at the ceiling, not follow the run");
        // The newest is still the one an undo reaches, which is the point of dropping the oldest.
        assertTrue(manager.latest().isPresent());
        manager.rollbackLatest();
        assertEquals(String.valueOf(CheckpointManager.MAX_CHECKPOINTS + 14),
                Files.readString(file));
    }
}
