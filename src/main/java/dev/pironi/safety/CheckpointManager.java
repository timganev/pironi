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
    private final Path checkpointRoot;
    private final Deque<Checkpoint> checkpoints = new ArrayDeque<>();

    public CheckpointManager(Workspace workspace) {
        this.workspace = workspace;
        this.checkpointRoot = workspace.root().resolve(".pironi/checkpoints");
    }

    public Checkpoint create(Path target) throws IOException {
        String id = UUID.randomUUID().toString();
        Path directory = checkpointRoot.resolve(id);
        Files.createDirectories(directory);
        boolean existed = Files.exists(target);
        if (existed) {
            Files.copy(target, directory.resolve("content"), StandardCopyOption.COPY_ATTRIBUTES);
        }
        Checkpoint checkpoint = new Checkpoint(
                id,
                workspace.root().relativize(target).toString(),
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

        Path target = workspace.resolveForWrite(checkpoint.relativePath());
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

    private static void deleteCheckpointDirectory(Checkpoint checkpoint) throws IOException {
        Files.deleteIfExists(checkpoint.directory().resolve("content"));
        Files.deleteIfExists(checkpoint.directory());
    }

    public record Checkpoint(
            String id,
            String relativePath,
            boolean existed,
            Path directory
    ) {
    }
}
