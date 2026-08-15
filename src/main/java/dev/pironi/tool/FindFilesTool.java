package dev.pironi.tool;

import dev.pironi.safety.AccessGrants;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class FindFilesTool implements Tool {
    private volatile AccessGrants grants = new AccessGrants();
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
                .map(FindFilesTool::canonicalize)
                .toList();
        this.hiddenPaths = hiddenPaths.stream()
                .map(FindFilesTool::canonicalize)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static Path canonicalize(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path.toAbsolutePath().normalize();
        }
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
            Set<Object> visitedDirectories = new HashSet<>();
            Set<Path> visitedRealDirectories = new HashSet<>();
            int[] visited = {0};
            Files.walkFileTree(realRoot, new FileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    if (++visited[0] > MAX_VISITED || results.size() >= maxResults) {
                        return FileVisitResult.TERMINATE;
                    }
                    if (!directory.equals(realRoot)
                            && (attributes.isSymbolicLink() || Files.isSymbolicLink(directory))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    try {
                        Path realDirectory = directory.toRealPath();
                        if (!visitedRealDirectories.add(realDirectory)) return FileVisitResult.SKIP_SUBTREE;
                        Object identity = attributes.fileKey();
                        if (identity != null && !visitedDirectories.add(identity)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                    } catch (IOException ignored) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path candidate, BasicFileAttributes attributes) {
                    if (++visited[0] > MAX_VISITED || results.size() >= maxResults) {
                        return FileVisitResult.TERMINATE;
                    }
                    if (!attributes.isRegularFile() || !matcher.matches(candidate.getFileName())) {
                        return FileVisitResult.CONTINUE;
                    }
                    try {
                        Path real = candidate.toRealPath();
                        if (!real.startsWith(realRoot)) return FileVisitResult.CONTINUE;
                        if (hiddenPaths.contains(candidate.toAbsolutePath().normalize())
                                || hiddenPaths.contains(real)) return FileVisitResult.CONTINUE;
                        if (!contains.isEmpty()) {
                            if (attributes.size() > MAX_CONTENT_BYTES) return FileVisitResult.CONTINUE;
                            String text = Files.readString(real, StandardCharsets.UTF_8);
                            if (!text.contains(contains)) return FileVisitResult.CONTINUE;
                        }
                        results.add(real.toString());
                    } catch (IOException ignored) {
                        // A single unreadable or transient path must not abort the whole search.
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException error) {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path directory, IOException error) {
                    return FileVisitResult.CONTINUE;
                }
            });
            return ToolResult.success(results.isEmpty() ? "No matches." : String.join("\n", results));
        } catch (IllegalArgumentException | IOException e) {
            return ToolResult.failure(e.getMessage());
        }
    }

    private Path selectedRoot(String requested) {
        if (requested.isBlank()) return allowedRoots.getFirst();
        Path normalized = canonicalize(Path.of(requested));
        return effectiveRoots().stream()
                .filter(normalized::startsWith)
                .findFirst()
                .map(ignored -> normalized)
                .orElseThrow(
                () -> new IllegalArgumentException("Search root is not allowed: " + requested)
        );
    }

    /** Lets the interactive shell widen this tool's reach without a restart. */
    public void useGrants(AccessGrants sessionGrants) {
        if (sessionGrants != null) this.grants = sessionGrants;
    }

    private java.util.List<java.nio.file.Path> effectiveRoots() {
        java.util.List<java.nio.file.Path> all = new java.util.ArrayList<>(allowedRoots);
        all.addAll(grants.grantedRoots());
        return all;
    }
}
