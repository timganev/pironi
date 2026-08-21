package dev.pironi.safety;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.UUID;

public final class CheckpointManager {
    private final Workspace workspace;
    private final Deque<Checkpoint> checkpoints = new ArrayDeque<>();

    public CheckpointManager(Workspace workspace) {
        this.workspace = workspace;
    }

    /** Read per call, not cached: /workspace moves the sandbox while the session runs. */
    private Path checkpointRoot() {
        return workspace.root().resolve(".pironi/checkpoints");
    }

    public Checkpoint create(Path target) throws IOException {
        String id = UUID.randomUUID().toString();
        Path directory = checkpointRoot().resolve(id);
        Files.createDirectories(directory);
        boolean existed = Files.exists(target);
        if (existed) {
            Files.copy(target, directory.resolve("content"), StandardCopyOption.COPY_ATTRIBUTES);
        }
        Checkpoint checkpoint = new Checkpoint(
                id,
                displayName(target),
                target.toAbsolutePath().normalize(),
                existed,
                directory
        );
        checkpoints.push(checkpoint);
        return checkpoint;
    }

    public Optional<Checkpoint> latest() {
        return Optional.ofNullable(checkpoints.peek());
    }

    public Checkpoint rollbackLatest() throws IOException {
        Checkpoint checkpoint = checkpoints.peek();
        if (checkpoint == null) {
            throw new IOException("No checkpoint is available in this session");
        }

        // The absolute path is stored with the checkpoint rather than re-resolved against the
        // workspace: after /workspace the current sandbox is a different directory, and restoring a
        // file into it would put it somewhere it never was.
        Path target = checkpoint.target();
        if (checkpoint.existed()) {
            Path staged = Files.createTempFile(target.getParent(), ".pironi-rollback-", ".tmp");
            try {
                Files.copy(
                        checkpoint.directory().resolve("content"),
                        staged,
                        StandardCopyOption.REPLACE_EXISTING
                );
                moveAtomically(staged, target);
            } finally {
                Files.deleteIfExists(staged);
            }
        } else {
            Files.deleteIfExists(target);
        }

        checkpoints.pop();
        deleteCheckpointDirectory(checkpoint);
        return checkpoint;
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Drops this session's copies: the rollback stack lives in memory and ends with the process.
     *
     * @return how many were removed
     */
    public int discardAll() {
        int removed = 0;
        for (Checkpoint checkpoint : checkpoints) {
            try {
                deleteCheckpointDirectory(checkpoint);
                removed++;
            } catch (IOException ignored) {
                // A checkpoint that will not delete is not worth failing a shutdown over.
            }
        }
        checkpoints.clear();
        return removed;
    }

    /**
     * Removes checkpoints from runs that never discarded theirs - a crash, a killed terminal.
     *
     * @return how many were removed
     */
    public int pruneOrphans(java.time.Duration olderThan) {
        Path root = checkpointRoot();
        if (!Files.isDirectory(root)) return 0;
        long cutoff = System.currentTimeMillis() - olderThan.toMillis();
        int removed = 0;
        try (var entries = Files.list(root)) {
            for (Path directory : entries.toList()) {
                try {
                    if (!Files.isDirectory(directory)) continue;
                    if (Files.getLastModifiedTime(directory).toMillis() > cutoff) continue;
                    Files.deleteIfExists(directory.resolve("content"));
                    Files.deleteIfExists(directory);
                    removed++;
                } catch (IOException ignored) {
                    // Skip what will not delete; the next run tries again.
                }
            }
        } catch (IOException ignored) {
            return removed;
        }
        return removed;
    }

    private static void deleteCheckpointDirectory(Checkpoint checkpoint) throws IOException {
        Files.deleteIfExists(checkpoint.directory().resolve("content"));
        Files.deleteIfExists(checkpoint.directory());
    }

    /** Names the file as the user saw it, relative while inside the workspace. */
    private String displayName(Path target) {
        Path absolute = Workspace.resolvedAsFarAsPossible(target);
        Path root = Workspace.resolvedAsFarAsPossible(workspace.root());
        return absolute.startsWith(root) ? root.relativize(absolute).toString() : absolute.toString();
    }

    public record Checkpoint(
            String id,
            String relativePath,
            Path target,
            boolean existed,
            Path directory
    ) {
    }
}
