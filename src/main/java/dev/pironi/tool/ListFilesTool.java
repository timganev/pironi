package dev.pironi.tool;

import com.fasterxml.jackson.databind.JsonNode;
import dev.pironi.safety.AccessGrants;
import dev.pironi.safety.Workspace;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ListFilesTool implements Tool {
    private volatile AccessGrants grants = new AccessGrants();
    private static final Set<String> IGNORED_DIRECTORIES = Set.of(
            ".git", ".pironi", ".idea", "target", "build", ".gradle", "node_modules"
    );
    /** Matches every other tool, so one listing of a deep tree cannot flood the context. */
    private static final int MAX_OUTPUT_CHARACTERS = ToolOutput.MAX_CHARACTERS;
    /** Upper bound on the walk itself, so profiling a huge tree stays bounded. */
    private static final int MAX_SCANNED_FILES = 200_000;

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
        return "List regular files. Relative paths use the workspace. "
                + ReadReach.describe(allowedRoots);
    }

    @Override
    public String argumentSchema() {
        return "{\"path\":\"string, required; relative workspace path or allowed absolute directory\"}";
    }

    @Override
    public boolean mutating() {
        return false;
    }

    /** Reading a credential store is refused unless a person says otherwise. */
    @Override
    public boolean requiresExplicitApproval(JsonNode arguments) {
        JsonNode supplied = arguments == null ? null : arguments.get("path");
        if (supplied == null || !supplied.isTextual()) return false;
        try {
            return dev.pironi.safety.SecretStores.isProtected(
                    java.nio.file.Path.of(supplied.textValue()));
        } catch (RuntimeException e) {
            return false;
        }
    }


    @Override
    public ToolResult execute(JsonNode arguments) {
        try {
            String path = ToolArguments.requiredText(arguments, "path");
            Path directory = resolveDirectory(path);
            if (!Files.isDirectory(directory)) {
                return ToolResult.failure("Not a directory: " + path);
            }

            List<Path> files = scan(directory);
            String listing = files.stream()
                    .limit(maxEntries)
                    .map(file -> file.startsWith(workspace.root())
                            ? workspace.root().relativize(file) : file)
                    .map(Path::toString)
                    .reduce((left, right) -> left + System.lineSeparator() + right)
                    .orElse("");
            if (files.size() <= maxEntries && listing.length() <= MAX_OUTPUT_CHARACTERS) {
                return ToolResult.success(listing);
            }
            return ToolResult.success(profile(directory, files));
        } catch (IllegalArgumentException | IOException e) {
            return ToolResult.failure(e);
        }
    }

    /**
     * The files under a directory, skipping what this account may not open.
     *
     * <p>Files.walk gives up the whole traversal on the first unreadable directory, and on Windows
     * a user's own tree has them: listing %LOCALAPPDATA%\Microsoft threw on INetCache\Content.IE5
     * and lost the Outlook data three directories over with it. One closed door is not a reason to
     * report nothing.
     */
    private List<Path> scan(Path directory) throws IOException {
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(directory, new java.nio.file.SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
                return isIgnored(directory.relativize(dir))
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (files.size() >= MAX_SCANNED_FILES) return FileVisitResult.TERMINATE;
                if (attributes.isRegularFile()
                        && !isIgnored(directory.relativize(file))
                        && !hiddenPaths.contains(canonicalize(file))) {
                    files.add(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException unreadable) {
                return FileVisitResult.CONTINUE;
            }
        });
        files.sort(Comparator.naturalOrder());
        return files;
    }

    private record Entry(Path relative, long bytes, long modified) {
    }

    /**
     * A truncated listing loses what matters in a big tree - how many files, of what kind, where
     * they cluster - so past the cap this reports shape instead of the first N paths.
     */
    private static String profile(Path directory, List<Path> files) {
        List<Entry> entries = new ArrayList<>(files.size());
        long totalBytes = 0;
        for (Path file : files) {
            long bytes = 0;
            long modified = 0;
            try {
                bytes = Files.size(file);
                modified = Files.getLastModifiedTime(file).toMillis();
            } catch (IOException ignored) {
                // a file that vanished mid-walk still counts toward the shape
            }
            totalBytes += bytes;
            entries.add(new Entry(directory.relativize(file), bytes, modified));
        }

        Map<String, Long> byExtension = entries.stream().collect(Collectors.groupingBy(
                entry -> extension(entry.relative()), Collectors.counting()
        ));
        Map<String, Long> byDirectory = entries.stream().collect(Collectors.groupingBy(
                entry -> entry.relative().getParent() == null
                        ? "." : entry.relative().getParent().toString(),
                Collectors.counting()
        ));

        StringBuilder out = new StringBuilder();
        out.append(entries.size()).append(" files, ").append(size(totalBytes))
                .append(" under ").append(directory);
        out.append(System.lineSeparator()).append("by extension: ").append(top(byExtension, 6));
        out.append(System.lineSeparator()).append("largest directories: ")
                .append(top(byDirectory, 5));
        out.append(System.lineSeparator()).append("newest: ").append(
                entries.stream()
                        .sorted(Comparator.comparingLong(Entry::modified).reversed())
                        .limit(3)
                        .map(entry -> entry.relative() + " (" + instant(entry.modified()) + ")")
                        .collect(Collectors.joining(", "))
        );
        out.append(System.lineSeparator()).append("largest: ").append(
                entries.stream()
                        .sorted(Comparator.comparingLong(Entry::bytes).reversed())
                        .limit(3)
                        .map(entry -> entry.relative() + " (" + size(entry.bytes()) + ")")
                        .collect(Collectors.joining(", "))
        );
        out.append(System.lineSeparator())
                .append("[too many entries to list; this is a profile. Use find_files with a "
                        + "pattern, or list one of the directories above.]");
        return out.toString();
    }

    private static String top(Map<String, Long> counts, int limit) {
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(limit)
                .map(entry -> entry.getKey() + " " + entry.getValue())
                .collect(Collectors.joining(", "));
    }

    private static String extension(Path relative) {
        String name = relative.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? "(none)" : name.substring(dot);
    }

    private static String size(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) return (bytes / (1024L * 1024 * 1024)) + " GB";
        if (bytes >= 1024L * 1024) return (bytes / (1024L * 1024)) + " MB";
        if (bytes >= 1024) return (bytes / 1024) + " KB";
        return bytes + " B";
    }

    private static String instant(long millis) {
        return java.time.Instant.ofEpochMilli(millis).toString().substring(0, 16).replace('T', ' ');
    }

    private Path resolveDirectory(String supplied) throws IOException {
        Path path = dev.pironi.safety.UserPath.of(supplied);
        if (!path.isAbsolute()) return workspace.resolveExisting(supplied);
        Path real = path.toRealPath();
        for (Path root : effectiveRoots()) {
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
