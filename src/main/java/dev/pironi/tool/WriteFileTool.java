package dev.pironi.tool;

import com.fasterxml.jackson.databind.JsonNode;
import dev.pironi.safety.CheckpointManager;
import dev.pironi.safety.Workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class WriteFileTool implements Tool {
    /**
     * Rewriting a whole file to change a few lines is the expensive way to edit: one run spent
     * 4,001 of its 9,952 output tokens retyping the same script three times.
     */
    private static final String OVERWRITE_HINT =
            ". The file already existed; apply_patch edits in place and costs far less than "
                    + "retyping a file to change part of it.";

    private final Workspace workspace;
    private final CheckpointManager checkpointManager;

    public WriteFileTool(Workspace workspace) {
        this(workspace, null);
    }

    public WriteFileTool(Workspace workspace, CheckpointManager checkpointManager) {
        this.workspace = workspace;
        this.checkpointManager = checkpointManager;
    }

    @Override
    public String name() {
        return "write_file";
    }

    @Override
    public String description() {
        return "Atomically create or replace a UTF-8 text file inside the workspace, creating "
                + "missing parent directories.";
    }

    @Override
    public String argumentSchema() {
        return "{\"path\":\"string, required\",\"content\":\"string, required\"}";
    }

    @Override
    public boolean mutating() {
        return true;
    }

    @Override
    public ToolResult validate(JsonNode arguments) {
        try {
            String path = ToolArguments.requiredText(arguments, "path");
            JsonNode contentNode = arguments.get("content");
            if (contentNode == null || !contentNode.isTextual()) {
                throw new IllegalArgumentException("content must be a string");
            }
            workspace.validateForWriteCreatingParents(path);
            return ToolResult.success("validated");
        } catch (IllegalArgumentException | IOException e) {
            return ToolResult.failure(e.getMessage());
        }
    }

    @Override
    public ToolResult execute(JsonNode arguments) {
        try {
            String path = ToolArguments.requiredText(arguments, "path");
            JsonNode contentNode = arguments.get("content");
            if (contentNode == null || !contentNode.isTextual()) {
                throw new IllegalArgumentException("content must be a string");
            }

            Path target = workspace.resolveForWriteCreatingParents(path);
            boolean overwrote = Files.exists(target);
            // apply_patch and move_file both snapshot before they touch anything, and this one did
            // not - so the safe tool could be undone and the destructive one could not.
            String checkpoint = overwrote && checkpointManager != null
                    ? checkpointManager.create(target).id()
                    : "";
            Path temporary = Files.createTempFile(target.getParent(), ".pironi-", ".tmp");
            try {
                Files.writeString(temporary, contentNode.textValue(), StandardCharsets.UTF_8);
                moveAtomically(temporary, target);
            } finally {
                Files.deleteIfExists(temporary);
            }
            return ToolResult.success("Wrote " + workspace.root().relativize(target)
                    + " (" + Files.size(target) + " bytes)"
                    + (checkpoint.isEmpty() ? "" : "; checkpoint=" + checkpoint)
                    + (overwrote ? OVERWRITE_HINT : ""));
        } catch (IllegalArgumentException | IOException e) {
            return ToolResult.failure(e.getMessage());
        }
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
}
