package dev.pironi.tool;

import com.fasterxml.jackson.databind.JsonNode;
import dev.pironi.safety.Workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ListFilesTool implements Tool {
    private static final Set<String> IGNORED_DIRECTORIES = Set.of(
            ".git", ".pironi", ".idea", "target", "build", ".gradle", "node_modules"
    );

    private final Workspace workspace;
    private final int maxEntries;
    private final List<Path> allowedRoots;
    private final Set<Path> hiddenPaths;

    public ListFilesTool(Workspace workspace, int maxEntries) {
        this(workspace, maxEntries, List.of(workspace.root()), Set.of());
    }

    public ListFilesTool(Workspace workspace, int maxEntries, Set<Path> hiddenPaths) {
        this(workspace, maxEntries, List.of(workspace.root()), hiddenPaths);
    }

    public ListFilesTool(
            Workspace workspace,
            int maxEntries,
            List<Path> readRoots,
            Set<Path> hiddenPaths
    ) {
        this.workspace = workspace;
        this.maxEntries = maxEntries;
        LinkedHashSet<Path> roots = new LinkedHashSet<>();
        roots.add(canonicalize(workspace.root()));
        readRoots.stream().map(ListFilesTool::canonicalize).forEach(roots::add);
        this.allowedRoots = List.copyOf(roots);
        this.hiddenPaths = hiddenPaths.stream()
                .map(ListFilesTool::canonicalize)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static Path canonicalize(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path.toAbsolutePath().normalize();
        }
    }

    @Override
    public String name() {
        return "list_files";
    }

    @Override
    public String description() {
        return "List regular files below the workspace or a configured search root. Relative "
                + "paths use the workspace; absolute paths are accepted below allowed roots: "
                + allowedRoots;
    }

    @Override
    public String argumentSchema() {
        return "{\"path\":\"string, required; relative workspace path or allowed absolute directory\"}";
    }

    @Override
    public boolean mutating() {
        return false;
    }

    @Override
    public ToolResult execute(JsonNode arguments) {
        try {
            String path = ToolArguments.requiredText(arguments, "path");
            Path directory = resolveDirectory(path);
            if (!Files.isDirectory(directory)) {
                return ToolResult.failure("Not a directory: " + path);
            }

            try (var files = Files.walk(directory)) {
                String output = files
                        .filter(Files::isRegularFile)
                        .filter(file -> !isIgnored(directory.relativize(file)))
                        .filter(file -> !hiddenPaths.contains(canonicalize(file)))
                        .sorted(Comparator.naturalOrder())
                        .limit(maxEntries)
                        .map(file -> file.startsWith(workspace.root())
                                ? workspace.root().relativize(file) : file)
                        .map(Path::toString)
                        .reduce((left, right) -> left + System.lineSeparator() + right)
                        .orElse("");
                return ToolResult.success(output);
            }
        } catch (IllegalArgumentException | IOException e) {
            return ToolResult.failure(e.getMessage());
        }
    }

    private Path resolveDirectory(String supplied) throws IOException {
        Path path = Path.of(supplied);
        if (!path.isAbsolute()) return workspace.resolveExisting(supplied);
        Path real = path.toRealPath();
        for (Path root : allowedRoots) {
            if (real.startsWith(root)) return real;
        }
        throw new IOException("Absolute path is outside configured search roots: " + supplied);
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
