package dev.pironi.session;

import java.io.IOException;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/** Filesystem-based skill store. */
public final class SkillStore {
    private static final int MAX_SKILL_CHARACTERS = 24_000;
    /** What a word in the name or triggers is worth, against one that is only in the prose. */
    private static final int NAMED_WORD = 2;

    private final Path skillsDir;

    public SkillStore(Path pironiHome) throws IOException {
        this.skillsDir = pironiHome.resolve("skills");
        Files.createDirectories(skillsDir);
        Files.createDirectories(skillsDir.resolve(".archive"));
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
                        if (isLoadable(md)) {
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
            return Optional.of(content);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * Why a skill was or was not applied.
     *
     * @param chosen the skill that will be applied, if any
     * @param reason chosen, no-match, below-threshold, tie, or empty-query
     * @param scores one "name=score/breadth" entry per skill considered, highest first
     */
    public record SkillDecision(Optional<SkillEntry> chosen, String reason, List<String> scores) {}

    public Optional<SkillEntry> findRelevant(String task) {
        return decide(task).chosen();
    }

    /**
     * Exclusions are matched one word at a time, not as phrases: "historical weather" keeps the
     * skill out of every request containing "weather". A skill whose exclusions were written as
     * behaviour instructions excludes itself on its own subject.
     */
    public SkillDecision decide(String task) {
        if (task == null || task.isBlank()) {
            return new SkillDecision(Optional.empty(), "empty-query", List.of());
        }
        Set<String> query = tokens(task);
        if (query.size() < 2) {
            return new SkillDecision(Optional.empty(), "empty-query", List.of());
        }
        List<String> scores = new ArrayList<>();
        try {
            SkillEntry best = null;
            int bestScore = 0;
            int bestBreadth = Integer.MAX_VALUE;
            boolean tied = false;
            for (SkillEntry entry : list()) {
                Set<String> exclusions = tokens(extractField(
                        Path.of(entry.path()), "exclusions:"
                ));
                if (!exclusions.isEmpty() && query.stream().anyMatch(
                        q -> exclusions.stream().anyMatch(e -> relatedForms(q, e)))) {
                    // Say so rather than dropping it silently. A skill whose exclusions were
                    // written as behaviour instructions excludes itself on its own subject, and
                    // vanishing from the scores makes that look like it never existed.
                    scores.add(entry.name() + "=excluded");
                    continue;
                }
                // A trigger is a promise about what this skill is for; a description is prose
                // written for a human, and any of its words can turn up in an unrelated
                // sentence. Counting them alike let "git status" reach a skill whose
                // description happens to say "status reports". Named words carry the weight.
                Set<String> named = tokens(entry.name() + " "
                        + extractField(Path.of(entry.path()), "triggers:"));
                Set<String> described = tokens(entry.description());
                int score = 0;
                for (String token : query) {
                    if (named.stream().anyMatch(m -> relatedForms(token, m))) score += NAMED_WORD;
                    else if (described.stream().anyMatch(m -> relatedForms(token, m))) score++;
                }
                Set<String> metadata = new HashSet<>(named);
                metadata.addAll(described);
                scores.add(entry.name() + "=" + score + "/" + metadata.size());
                if (score > bestScore) {
                    best = entry;
                    bestScore = score;
                    bestBreadth = metadata.size();
                    tied = false;
                } else if (score == bestScore && score > 0) {
                    // Equal hits: prefer a skill only when it is decisively narrower, because
                    // matching 3 of 5 trigger words says more than matching 3 of 40.
                    if (metadata.size() * 2 <= bestBreadth) {
                        best = entry;
                        bestBreadth = metadata.size();
                        tied = false;
                    } else if (bestBreadth * 2 > metadata.size()) {
                        tied = true;
                    }
                }
            }
            scores.sort(java.util.Comparator.comparingInt(SkillStore::scoreOf).reversed());
            // A named word is worth the threshold on its own: "give me an outlook report"
            // names its subject outright and used to apply nothing, because one word of prose
            // and one word of promise counted the same.
            String reason = bestScore == 0 ? "no-match"
                    : bestScore < NAMED_WORD ? "below-threshold"
                    : tied ? "tie"
                    : "chosen";
            return new SkillDecision(
                    "chosen".equals(reason) ? Optional.of(best) : Optional.empty(),
                    reason, List.copyOf(scores));
        } catch (IOException e) {
            return new SkillDecision(Optional.empty(), "error", List.copyOf(scores));
        }
    }

    /** An excluded entry carries no number; it sorts last rather than breaking the ordering. */
    private static int scoreOf(String line) {
        int equals = line.lastIndexOf('=');
        int slash = line.indexOf('/', equals);
        if (equals < 0 || slash < 0) return -1;
        try {
            return Integer.parseInt(line.substring(equals + 1, slash));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public boolean exists(String name) {
        return validName(name) && isLoadable(skillsDir.resolve(name).resolve("SKILL.md"));
    }

    /** The directory this store keeps skills in, for the few callers that need the path itself. */
    public Path directory() {
        return skillsDir;
    }

    /**
     * Where a skill came from: shipped with a release, shipped and then edited here, or written
     * here. Worth showing, because the three are trusted differently and only the first may be
     * replaced by a later release.
     */
    public BundledSkills.Origin origin(String name) {
        return BundledSkills.originOf(skillsDir, name);
    }

    public Optional<String> contentHash(String name) {
        if (!validName(name)) return Optional.empty();
        Path skill = skillsDir.resolve(name).resolve("SKILL.md");
        if (!isLoadable(skill)) return Optional.empty();
        try {
            return Optional.of(sha256(Files.readAllBytes(skill)));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    // ── save ───────────────────────────────────────────────────────────

    public boolean save(String name, String content) {
        if (name == null || name.isBlank() || content == null || content.isBlank()) return false;
        if (content.length() > MAX_SKILL_CHARACTERS) return false;
        try {
            String canonical = canonicalName(name);
            if (canonical.isBlank()) return false;
            Path dir = skillsDir.resolve(canonical);
            Files.createDirectories(dir);
            Path skill = dir.resolve("SKILL.md");
            if (Files.exists(skill)) return false;
            Path temporary = Files.createTempFile(dir, ".pironi-skill-", ".tmp");
            try {
                Files.writeString(temporary, SecretRedactor.redact(content), StandardCharsets.UTF_8);
                moveNewAtomically(temporary, skill);
            } finally {
                Files.deleteIfExists(temporary);
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public boolean replace(String name, String expectedHash, String content) {
        if (!validName(name) || expectedHash == null || expectedHash.isBlank()
                || content == null || content.isBlank()
                || content.length() > MAX_SKILL_CHARACTERS) return false;
        Path skill = skillsDir.resolve(name).resolve("SKILL.md");
        try {
            if (!isLoadable(skill)) return false;
            byte[] existing = Files.readAllBytes(skill);
            if (!sha256(existing).equals(expectedHash)) return false;
            Path version = skillsDir.resolve(".archive").resolve("versions").resolve(
                    name + "-" + System.currentTimeMillis() + "-" + expectedHash.substring(0, 8)
            );
            Files.createDirectories(version);
            Files.writeString(
                    version.resolve("SKILL.md"),
                    SecretRedactor.redact(new String(existing, StandardCharsets.UTF_8)),
                    StandardCharsets.UTF_8
            );
            Path temporary = Files.createTempFile(skill.getParent(), ".pironi-skill-", ".tmp");
            try {
                Files.writeString(temporary, SecretRedactor.redact(content), StandardCharsets.UTF_8);
                moveReplacingAtomically(temporary, skill);
            } finally {
                Files.deleteIfExists(temporary);
            }
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
            // A skill archived for going unused carries an old usage stamp, and restoring it
            // without a fresh one would hand it straight back to the next prune.
            markUsed(name);
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
            if (lastUsed(entry.name(), entry.mtime()).isBefore(cutoff)) {
                if (archive(entry.name())) pruned++;
            }
        }
        return pruned;
    }

    /**
     * Deletes archived skills for good once they have sat unclaimed. Archiving alone only moves a
     * folder, so without this nothing is ever actually removed and .archive grows for ever.
     */
    public int purgeArchived(int maxDaysArchived) throws IOException {
        int purged = 0;
        Instant cutoff = Instant.now().minus(maxDaysArchived, ChronoUnit.DAYS);
        Path archive = skillsDir.resolve(".archive");
        for (String name : listArchived()) {
            Path dir = archive.resolve(name);
            if (Instant.ofEpochMilli(dir.toFile().lastModified()).isBefore(cutoff)) {
                if (deleteRecursively(dir)) purged++;
            }
        }
        return purged;
    }

    private static boolean deleteRecursively(Path dir) {
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // ── usage ────────────────────────────────────────────────────

    /**
     * Records that a skill was applied. Staleness used to read SKILL.md's modification time, which
     * says when a skill was last edited, not when it was last used: a skill applied every day but
     * never corrected looked untouched and would have been archived out from under the user.
     */
    public void markUsed(String name) {
        if (!validName(name)) return;
        Path dir = skillsDir.resolve(name);
        if (!Files.isDirectory(dir)) return;
        try {
            Files.writeString(dir.resolve(".used"), Instant.now().toString(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            // A usage stamp that cannot be written is not worth failing a run over; the skill
            // keeps its previous stamp and stays eligible for a later prune.
        }
    }

    /** When a skill was last applied, falling back to when it was written. */
    public Instant lastUsed(String name, long fallbackMillis) {
        Path marker = skillsDir.resolve(name).resolve(".used");
        if (Files.isRegularFile(marker)) {
            try {
                return Instant.parse(Files.readString(marker, StandardCharsets.UTF_8).trim());
            } catch (IOException | RuntimeException e) {
                // An unreadable stamp is treated as no stamp rather than as age zero.
            }
        }
        return Instant.ofEpochMilli(fallbackMillis);
    }

    // ── helpers ────────────────────────────────────────────────────────

    private static String extractDescription(Path md) {
        try (BufferedReader reader = Files.newBufferedReader(md, StandardCharsets.UTF_8)) {
            String line;
            int inspected = 0;
            while ((line = reader.readLine()) != null && inspected++ < 40) {
                if (line.startsWith("description:")) {
                    return line.substring("description:".length()).trim().replace("\"", "");
                }
            }
        } catch (IOException ignored) { }
        return "(no description)";
    }

    private static String extractField(Path md, String field) {
        try (BufferedReader reader = Files.newBufferedReader(md, StandardCharsets.UTF_8)) {
            String line;
            int inspected = 0;
            while ((line = reader.readLine()) != null && inspected++ < 40) {
                if (line.startsWith(field)) {
                    return line.substring(field.length()).trim().replace("\"", "");
                }
            }
        } catch (IOException ignored) { }
        return "";
    }

    private static boolean isLoadable(Path path) {
        try {
            return Files.isRegularFile(path) && Files.size(path) <= MAX_SKILL_CHARACTERS;
        } catch (IOException e) {
            return false;
        }
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9_-]", "-").toLowerCase();
    }

    public static String canonicalName(String name) {
        if (name == null) return "";
        String canonical = sanitize(name).replaceAll("-{2,}", "-")
                .replaceAll("^-|-$", "");
        return canonical.matches("[a-z0-9][a-z0-9_-]*") ? canonical : "";
    }

    private static boolean validName(String name) {
        return name != null && name.matches("[a-zA-Z0-9_-]+");
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    private static Set<String> tokens(String value) {
        Set<String> result = new HashSet<>();
        for (String token : value.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (token.length() >= 3 && !STOP_WORDS.contains(token)) result.add(stem(token));
        }
        return result;
    }

    /** Strips only unambiguous endings; the rest is left to {@link #relatedForms}. */
    static String stem(String token) {
        for (String suffix : SUFFIXES) {
            if (token.length() - suffix.length() >= 4 && token.endsWith(suffix)) {
                return token.substring(0, token.length() - suffix.length());
            }
        }
        return token;
    }

    /** Longest first, so "ният" is tried before "ят" and "ing" before "ed". */
    private static final List<String> SUFFIXES = List.of(
            "ният", "ните", "ната", "ното", "ища", "ове", "ият", "ъта", "ния", "ята",
            "ing", "ers", "ed", "es"
    );

    /**
     * True when two tokens are forms of one word, judged by a shared prefix: exact matching missed
     * "седмичния статус" for "седмичен статус".
     */
    static boolean relatedForms(String left, String right) {
        int limit = Math.min(left.length(), right.length());
        int common = 0;
        while (common < limit && left.charAt(common) == right.charAt(common)) common++;
        return common >= 4 && common >= limit - 3;
    }

    private static final Set<String> STOP_WORDS = Set.of(
            "the", "and", "for", "with", "from", "this", "that",
            "как", "със", "или", "при", "това", "този", "тази", "към"
    );

    private static void moveNewAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target);
        }
    }

    private static void moveReplacingAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(
                    source, target,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value)
            );
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
