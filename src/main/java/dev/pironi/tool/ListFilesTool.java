package dev.pironi.tool;

import com.fasterxml.jackson.databind.JsonNode;
import dev.pironi.safety.Workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Set;

public final class ListFilesTool implements Tool {
    private static final Set<String> IGNORED_DIRECTORIES = Set.of(
            ".git", ".pironi", ".idea", "target", "build", ".gradle", "node_modules"
    );

    private final Workspace workspace;
    private final int maxEntries;
    private final Set<Path> hiddenPaths;

    public ListFilesTool(Workspace workspace, int maxEntries) {
        this(workspace, maxEntries, Set.of());
    }

    public ListFilesTool(Workspace workspace, int maxEntries, Set<Path> hiddenPaths) {
        this.workspace = workspace;
        this.maxEntries = maxEntries;
        this.hiddenPaths = hiddenPaths.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    @Override
    public String name() {
        return "list_files";
    }

    @Override
    public String description() {
        return "List regular files below a workspace-relative directory.";
    }

    @Override
    public String argumentSchema() {
        return "{\"path\":\"string, required; use . for workspace root\"}";
    }

    @Override
    public boolean mutating() {
        return false;
    }

    @Override
    public ToolResult execute(JsonNode arguments) {
        try {
            String path = ToolArguments.requiredText(arguments, "path");
            Path directory = workspace.resolveExisting(path);
            if (!Files.isDirectory(directory)) {
                return ToolResult.failure("Not a directory: " + path);
            }

            try (var files = Files.walk(directory)) {
                String output = files
                        .filter(Files::isRegularFile)
                        .filter(file -> !isIgnored(workspace.root().relativize(file)))
                        .filter(file -> !hiddenPaths.contains(file.toAbsolutePath().normalize()))
                        .sorted(Comparator.naturalOrder())
                        .limit(maxEntries)
                        .map(workspace.root()::relativize)
                        .map(Path::toString)
                        .reduce((left, right) -> left + System.lineSeparator() + right)
                        .orElse("");
                return ToolResult.success(output);
            }
        } catch (IllegalArgumentException | IOException e) {
            return ToolResult.failure(e.getMessage());
        }
    }

    private static boolean isIgnored(Path relativePath) {
        for (Path part : relativePath) {
            if (IGNORED_DIRECTORIES.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }
}
