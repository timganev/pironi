package dev.pironi.tool;

import com.fasterxml.jackson.databind.JsonNode;
import dev.pironi.safety.Workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ReadFileTool implements Tool {
    private final Workspace workspace;
    private final int maxCharacters;

    public ReadFileTool(Workspace workspace, int maxCharacters) {
        this.workspace = workspace;
        this.maxCharacters = maxCharacters;
    }

    @Override
    public String name() {
        return "read_file";
    }

    @Override
    public String description() {
        return "Read a UTF-8 text file inside the workspace.";
    }

    @Override
    public String argumentSchema() {
        return "{\"path\":\"string, required\"}";
    }

    @Override
    public boolean mutating() {
        return false;
    }

    @Override
    public ToolResult execute(JsonNode arguments) {
        try {
            String path = ToolArguments.requiredText(arguments, "path");
            Path file = workspace.resolveExisting(path);
            if (!Files.isRegularFile(file)) {
                return ToolResult.failure("Not a regular file: " + path);
            }
            String content = Files.readString(file, StandardCharsets.UTF_8);
            if (content.length() > maxCharacters) {
                return ToolResult.success(content.substring(0, maxCharacters)
                        + "\n[truncated after " + maxCharacters + " characters]");
            }
            return ToolResult.success(content);
        } catch (IllegalArgumentException | IOException e) {
            return ToolResult.failure(e.getMessage());
        }
    }
}
