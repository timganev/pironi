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

public final class ApplyPatchTool implements Tool {
    private static final int MAX_PREVIEW_CHARACTERS = 12_000;
    private static final long MAX_FILE_BYTES = 1_048_576;

    private final Workspace workspace;
    private final CheckpointManager checkpointManager;

    public ApplyPatchTool(Workspace workspace, CheckpointManager checkpointManager) {
        this.workspace = workspace;
        this.checkpointManager = checkpointManager;
    }

    @Override
    public String name() {
        return "apply_patch";
    }

    @Override
    public String description() {
        return "Replace one exact text fragment in a workspace file after showing a diff. "
                + "For a new file, oldText must be empty and its parent directory must exist.";
    }

    @Override
    public String argumentSchema() {
        return "{\"path\":\"string, required\",\"oldText\":\"string, required\","
                + "\"newText\":\"string, required\"}";
    }

    @Override
    public boolean mutating() {
        return true;
    }

    @Override
    public String approvalPreview(JsonNode arguments) {
        try {
            Patch patch = prepare(arguments);
            return patch.preview();
        } catch (IllegalArgumentException | IOException e) {
            return "Invalid patch: " + e.getMessage();
        }
    }

    @Override
    public ToolResult validate(JsonNode arguments) {
        try {
            prepare(arguments);
            return ToolResult.success("validated");
        } catch (IllegalArgumentException | IOException e) {
            return ToolResult.failure(e);
        }
    }

    @Override
    public ToolResult execute(JsonNode arguments) {
        try {
            Patch patch = prepare(arguments);
            var checkpoint = checkpointManager.create(patch.target());
            writeAtomically(patch.target(), patch.updatedContent());
            return ToolResult.success(
                    "Applied patch to " + patch.relativePath()
                            + "; checkpoint=" + checkpoint.id()
            );
        } catch (IllegalArgumentException | IOException e) {
            return ToolResult.failure(e);
        }
    }

    /** Where the text nearly matched, and how. */
    static String nearestLineHint(String original, String oldText) {
        String wanted = oldText.lines().findFirst().orElse("");
        if (wanted.isEmpty()) return "";

        String[] lines = original.split("\n", -1);
        String best = null;
        int bestNumber = 0;
        double bestScore = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].endsWith("\r")
                    ? lines[i].substring(0, lines[i].length() - 1) : lines[i];
            double score = similarity(wanted, line);
            if (score > bestScore) {
                bestScore = score;
                best = line;
                bestNumber = i + 1;
            }
        }
        // Below this the "closest" line is unrelated, and pointing at it would mislead.
        if (best == null || bestScore < 0.5) return "";

        int at = firstDifference(wanted, best);
        return ". The closest is line " + bestNumber + ", which differs at character "
                + (at + 1) + ": the file has " + describe(best, at)
                + " and oldText has " + describe(wanted, at)
                + ". Re-read the line and patch it exactly rather than rewriting the file.";
    }

    /** Matching positions over the longer of the two, which is cheap and enough to rank lines. */
    private static double similarity(String a, String b) {
        int longer = Math.max(a.length(), b.length());
        if (longer == 0) return 0;
        int shared = 0;
        for (int i = 0; i < Math.min(a.length(), b.length()); i++) {
            if (a.charAt(i) == b.charAt(i)) shared++;
        }
        return (double) shared / longer;
    }

    private static int firstDifference(String a, String b) {
        int i = 0;
        while (i < a.length() && i < b.length() && a.charAt(i) == b.charAt(i)) i++;
        return i;
    }

    /** A character a reader can act on: the codepoint, because the shape may be identical. */
    private static String describe(String text, int at) {
        if (at >= text.length()) return "nothing (the line ends here)";
        char c = text.charAt(at);
        String shown = switch (c) {
            case ' ' -> "a space";
            case '\t' -> "a tab";
            default -> "'" + c + "'";
        };
        return shown + String.format(" (U+%04X)", (int) c);
    }

    private Patch prepare(JsonNode arguments) throws IOException {
        String relativePath = ToolArguments.requiredText(arguments, "path");
        String oldText = requiredString(arguments, "oldText");
        String newText = requiredString(arguments, "newText");
        Path lexicalTarget = workspace.resolveForWrite(relativePath);
        boolean exists = Files.exists(lexicalTarget);

        Path existingTarget = exists ? workspace.resolveExisting(relativePath) : null;
        if (existingTarget != null && Files.size(existingTarget) > MAX_FILE_BYTES) {
            throw new IllegalArgumentException(
                    "File exceeds the " + MAX_FILE_BYTES + " byte patch limit: " + relativePath
            );
        }
        String original = exists
                ? Files.readString(existingTarget, StandardCharsets.UTF_8)
                : "";
        String updated;
        if (!exists) {
            if (!oldText.isEmpty()) {
                throw new IllegalArgumentException("oldText must be empty when creating a file");
            }
            updated = newText;
        } else {
            if (oldText.isEmpty()) {
                throw new IllegalArgumentException("oldText must not be empty for an existing file");
            }
            // A file checked out on Windows has CRLF endings while the model writes LF, so an exact
            // match fails on text that is plainly there and the error reads "not found".
            String targetOld = oldText;
            String targetNew = newText;
            int first = original.indexOf(targetOld);
            if (first < 0 && original.contains("\r\n") && !oldText.contains("\r")) {
                targetOld = oldText.replace("\n", "\r\n");
                targetNew = newText.replace("\n", "\r\n");
                first = original.indexOf(targetOld);
            }
            if (first < 0) {
                throw new IllegalArgumentException(
                        "oldText was not found in " + relativePath + nearestLineHint(original, oldText)
                );
            }
            if (original.indexOf(targetOld, first + targetOld.length()) >= 0) {
                throw new IllegalArgumentException(
                        "oldText occurs more than once in " + relativePath
                );
            }
            updated = original.substring(0, first)
                    + targetNew
                    + original.substring(first + targetOld.length());
        }

        return new Patch(
                relativePath,
                lexicalTarget,
                updated,
                diffPreview(relativePath, oldText, newText, exists)
        );
    }

    private static String requiredString(JsonNode arguments, String field) {
        JsonNode value = arguments.get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        return value.textValue();
    }

    private static String diffPreview(
            String path,
            String oldText,
            String newText,
            boolean existed
    ) {
        StringBuilder preview = new StringBuilder()
                .append("--- ").append(existed ? "a/" + path : "/dev/null").append('\n')
                .append("+++ b/").append(path).append('\n');
        oldText.lines().forEach(line -> preview.append('-').append(line).append('\n'));
        newText.lines().forEach(line -> preview.append('+').append(line).append('\n'));
        if (preview.length() > MAX_PREVIEW_CHARACTERS) {
            return preview.substring(0, MAX_PREVIEW_CHARACTERS)
                    + "\n[diff preview truncated]";
        }
        return preview.toString();
    }

    private static void writeAtomically(Path target, String content) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), ".pironi-", ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private record Patch(
            String relativePath,
            Path target,
            String updatedContent,
            String preview
    ) {
    }
}
