package dev.pironi.tool;

import com.fasterxml.jackson.databind.JsonNode;
import dev.pironi.safety.AccessGrants;
import dev.pironi.safety.Workspace;

import java.io.IOException;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ReadFileTool implements Tool {
    private volatile AccessGrants grants = new AccessGrants();
    private static final int DEFAULT_RANGE_LINES = 200;
    private static final int MAX_RANGE_LINES = 10_000;
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
                .map(ReadFileTool::canonicalize)
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
        return "read_file";
    }

    @Override
    public String description() {
        return "Read a UTF-8 text file inside the workspace or a configured read-only search "
                + "root. Absolute paths are accepted only below those roots. For large files, "
                + "use 1-based startLine with lineCount, or tailLines, to return a bounded "
                + "line range without loading the whole file.";
    }

    @Override
    public String argumentSchema() {
        return "{\"path\":\"string, required\",\"startLine\":\"integer 1..2147483647, optional\","
                + "\"lineCount\":\"integer 1..10000, optional\","
                + "\"tailLines\":\"integer 1..10000, optional; exclusive with startLine/lineCount\"}";
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
            boolean hasStart = arguments.hasNonNull("startLine");
            boolean hasCount = arguments.hasNonNull("lineCount");
            boolean hasTail = arguments.hasNonNull("tailLines");
            if (hasTail && (hasStart || hasCount)) {
                return ToolResult.failure("tailLines cannot be combined with startLine or lineCount");
            }
            if (hasTail) {
                int tailLines = ToolArguments.optionalPositiveInt(
                        arguments, "tailLines", 1, MAX_RANGE_LINES
                );
                return ToolResult.success(readTail(file, tailLines));
            }
            if (hasStart || hasCount) {
                int startLine = ToolArguments.optionalPositiveInt(
                        arguments, "startLine", 1, Integer.MAX_VALUE
                );
                int lineCount = ToolArguments.optionalPositiveInt(
                        arguments, "lineCount", DEFAULT_RANGE_LINES, MAX_RANGE_LINES
                );
                return ToolResult.success(readRange(file, startLine, lineCount));
            }
            String content = Files.readString(file, StandardCharsets.UTF_8);
            if (content.length() > maxCharacters) {
                return ToolResult.success(
                        content.substring(0, maxCharacters) + shortfall(content, maxCharacters)
                );
            }
            return ToolResult.success(content);
        } catch (IllegalArgumentException | IOException e) {
            return ToolResult.failure(e.getMessage());
        }
    }

    private String readRange(Path file, int startLine, int lineCount) throws IOException {
        StringBuilder output = new StringBuilder(Math.min(maxCharacters, 8_192));
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int currentLine = 0;
            int returned = 0;
            while ((line = reader.readLine()) != null) {
                currentLine++;
                if (currentLine < startLine) continue;
                if (returned >= lineCount) break;
                if (!appendBoundedLine(output, line)) return truncated(output);
                returned++;
            }
        }
        return output.toString();
    }

    private String readTail(Path file, int tailLines) throws IOException {
        Deque<String> tail = new ArrayDeque<>(tailLines);
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (tail.size() == tailLines) tail.removeFirst();
                tail.addLast(line);
            }
        }
        StringBuilder output = new StringBuilder(Math.min(maxCharacters, 8_192));
        for (String line : tail) {
            if (!appendBoundedLine(output, line)) return truncated(output);
        }
        return output.toString();
    }

    private boolean appendBoundedLine(StringBuilder output, String line) {
        int separator = output.isEmpty() ? 0 : 1;
        if (output.length() + separator + line.length() > maxCharacters) {
            int remaining = maxCharacters - output.length() - separator;
            if (separator == 1 && output.length() < maxCharacters) output.append('\n');
            if (remaining > 0) output.append(line, 0, remaining);
            return false;
        }
        if (separator == 1) output.append('\n');
        output.append(line);
        return true;
    }

    private String truncated(StringBuilder output) {
        return output + "\n[truncated after " + maxCharacters + " characters; continue with "
                + "startLine/lineCount, or tailLines for the end]";
    }

    /**
     * A bare "truncated" reads as a dead end, so the agent gives up on the file or re-reads the
     * same head. Saying how much is left, and how to ask for it, turns it into a page boundary.
     */
    private static String shortfall(String content, int shown) {
        long totalLines = content.lines().count();
        long shownLines = content.substring(0, shown).lines().count();
        return System.lineSeparator()
                + "[truncated after " + shown + " of " + content.length() + " characters; showed "
                + "lines 1-" + shownLines + " of " + totalLines + ". Continue with startLine="
                + (shownLines + 1) + " and lineCount, or use tailLines for the end.]";
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
        for (Path root : grants.grantedRoots()) {
            if (Files.exists(root)) roots.add(root.toRealPath());
        }
        return roots;
    }

    /** Lets the interactive shell widen this tool's reach without a restart. */
    public void useGrants(AccessGrants sessionGrants) {
        if (sessionGrants != null) this.grants = sessionGrants;
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
