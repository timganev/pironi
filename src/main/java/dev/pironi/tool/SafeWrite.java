package dev.pironi.tool;

import dev.pironi.safety.CheckpointManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Producing a file the way {@code write_file} does it.
 *
 * <p>Three tools created documents with a plain write and no snapshot, while the two general
 * write tools beside them staged into a temporary file, moved it into place, and checkpointed
 * first. So {@code csv_sanitize} pointed at an existing file destroyed it with nothing to roll
 * back to, and a process killed mid-write left a half-written document where a whole one had
 * been - in the tools whose entire output is a document.
 */
final class SafeWrite {
    private SafeWrite() {
    }

    /**
     * Snapshots {@code target} if it exists, so the write can be undone.
     *
     * @return the checkpoint id, or empty when there was nothing to snapshot
     */
    static String snapshot(CheckpointManager manager, Path target) throws IOException {
        if (manager == null || !Files.exists(target)) return "";
        return manager.create(target).id();
    }

    static void write(Path target, String content) throws IOException {
        write(target, content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Writes through a temporary file in the same directory, so the move that publishes it is a
     * rename on one filesystem rather than a copy that can stop halfway.
     */
    static void write(Path target, byte[] content) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), ".pironi-", ".tmp");
        try {
            Files.write(temporary, content);
            try {
                Files.move(temporary, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException acrossFilesystems) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** The note a tool adds to its answer so an undo is reachable without guessing. */
    static String checkpointNote(String checkpoint) {
        return checkpoint.isEmpty() ? "" : "; checkpoint=" + checkpoint;
    }
}
