package dev.pironi.tool;

import com.fasterxml.jackson.databind.JsonNode;
import dev.pironi.safety.CheckpointManager;
import dev.pironi.safety.Workspace;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class MoveFileTool implements Tool {
    private final Workspace workspace;
    private final CheckpointManager checkpoints;

    public MoveFileTool(Workspace workspace, CheckpointManager checkpoints) {
        this.workspace = workspace;
        this.checkpoints = checkpoints;
    }

    @Override public String name() { return "move_file"; }
    @Override public String description() {
        return "Move one file inside the workspace without overwriting the destination.";
    }
    @Override public String argumentSchema() {
        return "{\"source\":\"string, required\",\"destination\":\"string, required\"}";
    }
    @Override public boolean mutating() { return true; }

    @Override
    public ToolResult validate(JsonNode arguments) {
        try {
            Path source = workspace.resolveExisting(
                    ToolArguments.requiredText(arguments, "source")
            );
            Path destination = workspace.validateForWriteCreatingParents(
                    ToolArguments.requiredText(arguments, "destination")
            );
            if (!Files.isRegularFile(source)) throw new IOException("Source is not a regular file");
            if (Files.exists(destination)) throw new IOException("Destination already exists");
            if (source.equals(destination)) throw new IOException("Source and destination are identical");
            return ToolResult.success("validated");
        } catch (IllegalArgumentException | IOException e) {
            return ToolResult.failure(e.getMessage());
        }
    }

    @Override
    public ToolResult execute(JsonNode arguments) {
        try {
            Path source = workspace.resolveExisting(ToolArguments.requiredText(arguments, "source"));
            Path destination = workspace.resolveForWriteCreatingParents(
                    ToolArguments.requiredText(arguments, "destination")
            );
            if (!Files.isRegularFile(source)) throw new IOException("Source is not a regular file");
            if (Files.exists(destination)) throw new IOException("Destination already exists");
            if (source.equals(destination)) throw new IOException("Source and destination are identical");
            String before = sha256(source);
            checkpoints.create(source);
            checkpoints.create(destination);
            try {
                Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(source, destination);
            }
            String after = sha256(destination);
            if (!before.equals(after)) throw new IOException("Checksum changed after move");
            return ToolResult.success("Moved " + workspace.root().relativize(source) + " to "
                    + workspace.root().relativize(destination) + " (sha256=" + after + ")");
        } catch (IllegalArgumentException | IOException e) {
            return ToolResult.failure(e.getMessage());
        }
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
