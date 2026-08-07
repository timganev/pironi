package dev.pironi.tool;

import com.fasterxml.jackson.databind.JsonNode;
import dev.pironi.safety.Workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ReadFileTool implements Tool {
    private final Workspace workspace;
    private final int maxCharacters;
    private final List<Path> allowedRoots;
    private final Set<Path> hiddenPaths;

    public ReadFileTool(Workspace workspace, int maxCharacters) {
        this(workspace, maxCharacters, List.of(workspace.root()), Set.of());
    }

    public ReadFileTool(
            Workspace workspace,
            int maxCharacters,
            List<Path> readRoots,
            Set<Path> hiddenPaths
    ) {
        this.workspace = workspace;
        this.maxCharacters = maxCharacters;
        LinkedHashSet<Path> roots = new LinkedHashSet<>();
        roots.add(workspace.root());
        readRoots.stream().map(ReadFileTool::absoluteNormalized).forEach(roots::add);
        this.allowedRoots = List.copyOf(roots);
        this.hiddenPaths = hiddenPaths.stream()
                .map(ReadFileTool::absoluteNormalized)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    @Override
    public String name() {
        return "read_file";
    }

    @Override
    public String description() {
        return "Read a UTF-8 text file inside the workspace or a configured read-only search "
                + "root. Absolute paths are accepted only below those roots.";
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
            Path file = resolve(path);
            if (isHidden(file)) {
                return ToolResult.failure("File is hidden from agent tools: " + path);
            }
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

    private Path resolve(String supplied) throws IOException {
        Path path = Path.of(supplied);
        if (!path.isAbsolute()) return workspace.resolveExisting(supplied);
        Path real = path.toRealPath();
        for (Path root : realAllowedRoots()) {
            if (real.startsWith(root)) return real;
        }
        throw new IOException("Absolute path is outside configured read roots: " + supplied);
    }

    private List<Path> realAllowedRoots() throws IOException {
        List<Path> roots = new ArrayList<>();
        for (Path root : allowedRoots) {
            if (Files.exists(root)) roots.add(root.toRealPath());
        }
        return roots;
    }

    private boolean isHidden(Path path) throws IOException {
        Path normalized = absoluteNormalized(path);
        Path real = path.toRealPath();
        return hiddenPaths.contains(normalized) || hiddenPaths.contains(real);
    }

    private static Path absoluteNormalized(Path path) {
        return path.toAbsolutePath().normalize();
    }
}
