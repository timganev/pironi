package dev.pironi.session;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Replacing a whole file without a moment where it is half of one.
 *
 * <p>SkillStore and BundledSkills already staged into a temporary file and renamed it into place;
 * the stores beside them overwrote in place, so a run interrupted mid-write left a truncated
 * findings file, session index or USER.md. The findings loader swallows an IOException and returns
 * nothing, so a truncated file would not even have surfaced as an error - the carry-over would
 * simply have been shorter than it was the day before, and nobody would know which entries went.
 *
 * <p>Only for files written whole. An append - the session transcript - is already safe: a partial
 * last line is the only thing at risk, and rewriting the file to add one would be worse.
 */
final class AtomicFile {
    private AtomicFile() {
    }

    static void writeString(Path target, String content) throws IOException {
        write(target, content.getBytes(StandardCharsets.UTF_8));
    }

    static void writeLines(Path target, List<String> lines) throws IOException {
        write(target, (String.join("\n", lines) + (lines.isEmpty() ? "" : "\n"))
                .getBytes(StandardCharsets.UTF_8));
    }

    private static void write(Path target, byte[] content) throws IOException {
        Path directory = target.getParent();
        if (directory != null) Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, ".pironi-", ".tmp");
        try {
            Files.write(temporary, content);
            try {
                Files.move(temporary, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException acrossFilesystems) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
