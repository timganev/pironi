package dev.pironi.session;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Plants the skills a release carries into the store the user actually reads, and remembers which
 * ones came from us.
 *
 * <p>A release ships {@code skills/} beside its launcher: seed, not store. Everything Pironi reads
 * lives in one place under the home, so a skill written during a session and a skill that arrived
 * with the download sit side by side and behave the same. What has to be remembered is which was
 * which, and whether the person has since changed it - three states that decide whether the next
 * release may replace a file.
 */
public final class BundledSkills {
    /** One line per shipped skill: name, its content hash when planted, the release that brought it. */
    static final String MANIFEST = ".bundled";
    private static final String SEPARATOR = "\t";

    private BundledSkills() {
    }

    /** Where a skill in the store came from, as far as anyone can tell from the manifest. */
    public enum Origin {
        /** Shipped with a release and untouched since. */
        SHIPPED,
        /** Shipped with a release, then edited here. The next release must not overwrite it. */
        SHIPPED_EDITED,
        /** Written here, by the person or by the agent on their behalf. */
        LOCAL
    }

    public record Record(String name, String hash, String version) {
    }

    /**
     * Copies what the release carries into the store.
     *
     * <p>Four cases, and the last two are why the manifest exists at all:
     * <ul>
     *   <li>not in the store yet - plant it</li>
     *   <li>in the store, unchanged since we planted it - a newer release may replace it</li>
     *   <li>in the store and edited here - it is theirs now, leave it, keep remembering it was ours</li>
     *   <li>in the manifest but gone from the store - they deleted it, and planting it again would
     *       be a program arguing with its user</li>
     * </ul>
     *
     * @return the names planted or updated, in order, empty when there was nothing to do
     */
    public static List<String> install(Path seedDir, Path skillsDir, String version)
            throws IOException {
        if (seedDir == null || !Files.isDirectory(seedDir)) return List.of();
        Files.createDirectories(skillsDir);
        Map<String, Record> manifest = readManifest(skillsDir);
        List<String> planted = new ArrayList<>();

        for (Path seed : seeds(seedDir)) {
            String name = seed.getFileName().toString();
            Path source = seed.resolve("SKILL.md");
            if (!Files.isRegularFile(source)) continue;
            Record known = manifest.get(name);
            Path target = skillsDir.resolve(name).resolve("SKILL.md");
            String current = Files.isRegularFile(target) ? hash(Files.readAllBytes(target)) : null;

            if (current == null && known != null) continue;          // deleted here; stays deleted
            if (current != null && known == null) continue;          // theirs, same name; not ours to touch
            if (current != null && !current.equals(known.hash())) continue;  // edited here

            byte[] content = Files.readAllBytes(source);
            String planted_hash = hash(content);
            if (planted_hash.equals(current)) {
                manifest.put(name, new Record(name, planted_hash, version));
                continue;                                             // already identical
            }
            Files.createDirectories(target.getParent());
            Files.write(target, content);
            plantCompanions(seed, target.getParent());
            manifest.put(name, new Record(name, planted_hash, version));
            planted.add(name);
        }

        writeManifest(skillsDir, manifest);
        return List.copyOf(planted);
    }

    /**
     * The rest of a skill's directory: a helper script, a template, a sample file. The packagers
     * copy a skill folder whole, so anything alongside SKILL.md reaches the release - and planting
     * only SKILL.md left the skill in the store referring to a file that was never written. The
     * failure is silent and only appears when the skill is used.
     *
     * <p>SKILL.md is the identity, so it alone is hashed and tracked; a companion is overwritten
     * with what the release carries, because a skill and its script have to be one version.
     */
    private static void plantCompanions(Path seed, Path target) throws IOException {
        try (Stream<Path> entries = Files.list(seed)) {
            for (Path entry : entries.filter(Files::isRegularFile).toList()) {
                String fileName = entry.getFileName().toString();
                if (fileName.equals("SKILL.md")) continue;
                Files.copy(entry, target.resolve(fileName),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /** What the store can say about where a skill came from. */
    public static Origin originOf(Path skillsDir, String name) {
        try {
            Record known = readManifest(skillsDir).get(name);
            if (known == null) return Origin.LOCAL;
            Path skill = skillsDir.resolve(name).resolve("SKILL.md");
            if (!Files.isRegularFile(skill)) return Origin.LOCAL;
            return hash(Files.readAllBytes(skill)).equals(known.hash())
                    ? Origin.SHIPPED : Origin.SHIPPED_EDITED;
        } catch (IOException e) {
            return Origin.LOCAL;
        }
    }

    /** Restores a shipped skill to the wording the release carried, or false when it cannot. */
    public static boolean reset(Path seedDir, Path skillsDir, String name, String version) {
        if (seedDir == null) return false;
        Path source = seedDir.resolve(name).resolve("SKILL.md");
        if (!Files.isRegularFile(source)) return false;
        try {
            Path target = skillsDir.resolve(name).resolve("SKILL.md");
            Files.createDirectories(target.getParent());
            byte[] content = Files.readAllBytes(source);
            Files.write(target, content);
            Map<String, Record> manifest = readManifest(skillsDir);
            manifest.put(name, new Record(name, hash(content), version));
            writeManifest(skillsDir, manifest);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    static Map<String, Record> readManifest(Path skillsDir) throws IOException {
        Map<String, Record> records = new LinkedHashMap<>();
        Path file = skillsDir.resolve(MANIFEST);
        if (!Files.isRegularFile(file)) return records;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String[] parts = line.split(SEPARATOR, 3);
            if (parts.length < 2 || parts[0].isBlank()) continue;
            records.put(parts[0], new Record(parts[0], parts[1], parts.length > 2 ? parts[2] : ""));
        }
        return records;
    }

    private static void writeManifest(Path skillsDir, Map<String, Record> records)
            throws IOException {
        Path file = skillsDir.resolve(MANIFEST);
        Path temporary = Files.createTempFile(skillsDir, ".bundled-", ".tmp");
        try {
            Files.write(temporary, records.values().stream()
                    .map(r -> r.name() + SEPARATOR + r.hash() + SEPARATOR + r.version())
                    .toList(), StandardCharsets.UTF_8);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static List<Path> seeds(Path seedDir) throws IOException {
        try (Stream<Path> entries = Files.list(seedDir)) {
            return entries
                    .filter(Files::isDirectory)
                    .filter(d -> !d.getFileName().toString().startsWith("."))
                    .sorted()
                    .toList();
        }
    }

    private static String hash(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the platform", e);
        }
    }
}
