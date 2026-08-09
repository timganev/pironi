package dev.pironi.session;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Filesystem-based skill store. SKILL.md files with YAML frontmatter.
 */
public final class SkillStore {
    private static final int MAX_PROMPT_INDEX_CHARACTERS = 2_400;
    private static final int MAX_PROMPT_INDEX_ENTRIES = 24;
    private static final int MAX_SKILL_CHARACTERS = 24_000;

    private final Path skillsDir;

    public SkillStore(Path pironiHome) throws IOException {
        this.skillsDir = pironiHome.resolve("skills");
        Files.createDirectories(skillsDir);
        Files.createDirectories(skillsDir.resolve(".archive"));
        ensureIndex();
    }

    // ── list ───────────────────────────────────────────────────────────

    public record SkillEntry(String name, String description, String path, long mtime) {}

    public List<SkillEntry> list() throws IOException {
        var entries = new ArrayList<SkillEntry>();
        try (Stream<Path> dirs = Files.list(skillsDir)) {
            dirs.filter(Files::isDirectory)
                    .filter(d -> !d.getFileName().toString().startsWith("."))
                    .forEach(dir -> {
                        Path md = dir.resolve("SKILL.md");
                        if (Files.exists(md)) {
                            String name = dir.getFileName().toString();
                            String desc = extractDescription(md);
                            long mtime = md.toFile().lastModified();
                            entries.add(new SkillEntry(name, desc, md.toString(), mtime));
                        }
                    });
        }
        entries.sort((a, b) -> Long.compare(b.mtime, a.mtime));
        return entries;
    }

    // ── load ───────────────────────────────────────────────────────────

    public Optional<String> load(String name) {
        if (!validName(name)) return Optional.empty();
        Path md = skillsDir.resolve(name).resolve("SKILL.md");
        if (!Files.exists(md)) return Optional.empty();
        try {
            String content = Files.readString(md, StandardCharsets.UTF_8);
            if (content.length() > MAX_SKILL_CHARACTERS) return Optional.empty();
            // Touch file for mtime tracking
            md.toFile().setLastModified(System.currentTimeMillis());
            return Optional.of(content);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    // ── save ───────────────────────────────────────────────────────────

    public boolean save(String name, String content) {
        if (name == null || name.isBlank() || content == null || content.isBlank()) return false;
        if (content.length() > MAX_SKILL_CHARACTERS) return false;
        try {
            Path dir = skillsDir.resolve(sanitize(name));
            Files.createDirectories(dir);
            Files.writeString(
                    dir.resolve("SKILL.md"), SecretRedactor.redact(content), StandardCharsets.UTF_8
            );
            rebuildIndex();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // ── archive / restore ──────────────────────────────────────────────

    public boolean archive(String name) {
        if (!validName(name)) return false;
        try {
            Path src = skillsDir.resolve(name);
            Path dst = skillsDir.resolve(".archive").resolve(name);
            if (!Files.exists(src)) return false;
            Files.move(src, dst);
            rebuildIndex();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public boolean restore(String name) {
        if (!validName(name)) return false;
        try {
            Path src = skillsDir.resolve(".archive").resolve(name);
            Path dst = skillsDir.resolve(name);
            if (!Files.exists(src)) return false;
            Files.move(src, dst);
            rebuildIndex();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public List<String> listArchived() throws IOException {
        var names = new ArrayList<String>();
        Path archive = skillsDir.resolve(".archive");
        try (Stream<Path> dirs = Files.list(archive)) {
            dirs.filter(Files::isDirectory)
                    .forEach(d -> names.add(d.getFileName().toString()));
        }
        return names;
    }

    // ── prune ──────────────────────────────────────────────────────────

    public int pruneStale(int maxDaysUnused) throws IOException {
        int pruned = 0;
        Instant cutoff = Instant.now().minus(maxDaysUnused, ChronoUnit.DAYS);
        for (SkillEntry entry : list()) {
            if (Instant.ofEpochMilli(entry.mtime()).isBefore(cutoff)) {
                if (archive(entry.name())) pruned++;
            }
        }
        return pruned;
    }

    // ── index ──────────────────────────────────────────────────────────

    private void ensureIndex() throws IOException {
        Path index = skillsDir.resolve("INDEX.md");
        if (!Files.exists(index)) {
            Files.writeString(index, "# Pironi Skills\n\n", StandardCharsets.UTF_8);
        }
    }

    public String loadIndex() {
        try {
            List<SkillEntry> available = list();
            if (available.isEmpty()) return "";
            StringBuilder compact = new StringBuilder("# Pironi Skills\n\n");
            int count = 0;
            for (SkillEntry entry : available) {
                if (count >= MAX_PROMPT_INDEX_ENTRIES) break;
                String line = "- **" + entry.name() + "**: "
                        + truncate(entry.description(), 80) + "\n";
                if (compact.length() + line.length() > MAX_PROMPT_INDEX_CHARACTERS) break;
                compact.append(line);
                count++;
            }
            return compact.toString();
        } catch (IOException e) {
            return "";
        }
    }

    private void rebuildIndex() throws IOException {
        var sb = new StringBuilder("# Pironi Skills\n\n");
        for (SkillEntry entry : list()) {
            sb.append("- **").append(entry.name()).append("**: ")
                    .append(truncate(entry.description(), 80)).append("\n");
        }
        Files.writeString(skillsDir.resolve("INDEX.md"), sb.toString(), StandardCharsets.UTF_8);
    }

    // ── helpers ────────────────────────────────────────────────────────

    private static String extractDescription(Path md) {
        try {
            for (String line : Files.readAllLines(md, StandardCharsets.UTF_8)) {
                if (line.startsWith("description:")) {
                    return line.substring("description:".length()).trim().replace("\"", "");
                }
            }
        } catch (IOException ignored) { }
        return "(no description)";
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9_-]", "-").toLowerCase();
    }

    private static boolean validName(String name) {
        return name != null && name.matches("[a-zA-Z0-9_-]+");
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }
}
