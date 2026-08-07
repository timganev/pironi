package dev.pironi.tool;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class FindFilesTool implements Tool {
    private static final int MAX_VISITED = 20_000;
    private static final int MAX_CONTENT_BYTES = 2 * 1024 * 1024;
    private final List<Path> allowedRoots;
    private final Set<Path> hiddenPaths;

    public FindFilesTool(List<Path> allowedRoots) {
        this(allowedRoots, Set.of());
    }

    public FindFilesTool(List<Path> allowedRoots, Set<Path> hiddenPaths) {
        if (allowedRoots.isEmpty()) throw new IllegalArgumentException("At least one search root is required");
        this.allowedRoots = allowedRoots.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .toList();
        this.hiddenPaths = hiddenPaths.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    @Override public String name() { return "find_files"; }
    @Override public String description() {
        return "Find files only below configured search roots, optionally by glob and text content. "
                + "Allowed roots: " + allowedRoots;
    }
    @Override public String argumentSchema() {
        return "{\"root\":\"allowed absolute root, optional\",\"name\":\"glob, optional\","
                + "\"contains\":\"text, optional\",\"maxResults\":\"integer, optional, max 100\"}";
    }
    @Override public boolean mutating() { return false; }

    @Override
    public ToolResult execute(JsonNode arguments) {
        try {
            Path root = selectedRoot(arguments.path("root").asText(""));
            String name = arguments.path("name").asText("*");
            String contains = arguments.path("contains").asText("");
            int maxResults = ToolArguments.optionalPositiveInt(arguments, "maxResults", 20, 100);
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + name);
            Path realRoot = root.toRealPath();
            List<String> results = new ArrayList<>();
            int visited = 0;
            try (var paths = Files.walk(realRoot)) {
                for (Path candidate : (Iterable<Path>) paths::iterator) {
                    if (++visited > MAX_VISITED || results.size() >= maxResults) break;
                    if (!Files.isRegularFile(candidate) || !matcher.matches(candidate.getFileName())) continue;
                    Path real = candidate.toRealPath();
                    if (!real.startsWith(realRoot)) continue;
                    if (hiddenPaths.contains(candidate.toAbsolutePath().normalize())
                            || hiddenPaths.contains(real)) continue;
                    if (!contains.isEmpty()) {
                        if (Files.size(real) > MAX_CONTENT_BYTES) continue;
                        String text = Files.readString(real, StandardCharsets.UTF_8);
                        if (!text.contains(contains)) continue;
                    }
                    results.add(real.toString());
                }
            }
            return ToolResult.success(results.isEmpty() ? "No matches." : String.join("\n", results));
        } catch (IllegalArgumentException | IOException e) {
            return ToolResult.failure(e.getMessage());
        }
    }

    private Path selectedRoot(String requested) {
        if (requested.isBlank()) return allowedRoots.getFirst();
        Path normalized = Path.of(requested).toAbsolutePath().normalize();
        return allowedRoots.stream().filter(normalized::equals).findFirst().orElseThrow(
                () -> new IllegalArgumentException("Search root is not allowed: " + requested)
        );
    }
}
