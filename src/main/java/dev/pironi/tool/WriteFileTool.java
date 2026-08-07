package dev.pironi.tool;

import com.fasterxml.jackson.databind.JsonNode;
import dev.pironi.safety.Workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class WriteFileTool implements Tool {
    private final Workspace workspace;

    public WriteFileTool(Workspace workspace) {
        this.workspace = workspace;
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
            Path temporary = Files.createTempFile(target.getParent(), ".pironi-", ".tmp");
            try {
                Files.writeString(temporary, contentNode.textValue(), StandardCharsets.UTF_8);
                moveAtomically(temporary, target);
            } finally {
                Files.deleteIfExists(temporary);
            }
            return ToolResult.success("Wrote " + workspace.root().relativize(target)
                    + " (" + Files.size(target) + " bytes)");
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
