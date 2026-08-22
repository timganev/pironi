package dev.pironi.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The skills in {@code skills/} ship inside every release bundle and are read by SkillStore on the
 * machine that unzips it. Nothing between here and there checks them, so a skill that breaks one of
 * the store's rules is packaged, shipped, and then silently not loaded - a failure that costs a
 * release to notice. These are the store's own rules, asserted where they can still be fixed.
 */
class BundledSkillsTest {
    private static final Path SKILLS = Path.of("skills");
    private static final int MAX_SKILL_CHARACTERS = 24_000;

    @TempDir Path home;

    private static List<Path> bundled() throws Exception {
        if (!Files.isDirectory(SKILLS)) return List.of();
        try (Stream<Path> entries = Files.list(SKILLS)) {
            return entries.filter(Files::isDirectory).sorted().toList();
        }
    }

    @Test
    void everyBundledSkillIsOneTheStoreWillLoad() throws Exception {
        List<String> problems = new ArrayList<>();
        for (Path skill : bundled()) {
            String name = skill.getFileName().toString();
            // SkillStore.validName: anything else cannot be addressed by /skill or skill tools.
            if (!name.matches("[a-zA-Z0-9_-]+")) {
                problems.add(name + ": the directory name must be [a-zA-Z0-9_-]+");
            }
            Path md = skill.resolve("SKILL.md");
            if (!Files.isRegularFile(md)) {
                problems.add(name + ": has no SKILL.md, so the store will not list it");
                continue;
            }
            String content = Files.readString(md, StandardCharsets.UTF_8);
            if (content.length() > MAX_SKILL_CHARACTERS) {
                problems.add(name + ": " + content.length() + " characters, over the "
                        + MAX_SKILL_CHARACTERS + " the store loads");
            }
            if (content.isBlank()) problems.add(name + ": SKILL.md is empty");
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void everyBundledSkillSaysWhatItIsFor() throws Exception {
        List<String> problems = new ArrayList<>();
        for (Path skill : bundled()) {
            Path md = skill.resolve("SKILL.md");
            if (!Files.isRegularFile(md)) continue;
            // extractDescription reads the first 40 lines for one starting "description:", and a
            // skill without one is listed to the model as "(no description)" - which is how a
            // shipped skill gets ignored despite being right there.
            boolean described = Files.readAllLines(md, StandardCharsets.UTF_8).stream()
                    .limit(40)
                    .anyMatch(line -> line.startsWith("description:")
                            && !line.substring("description:".length()).isBlank());
            if (!described) {
                problems.add(skill.getFileName()
                        + ": needs a 'description:' line in its first 40 lines");
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void aBundledSkillIsVisibleOnceItIsInTheHome() throws Exception {
        // What the release does: the packagers copy skills/* into <bundle>/.pironi/skills, and the
        // launcher points the home at <bundle>/.pironi. This is that arrangement, asserted.
        List<Path> skills = bundled();
        if (skills.isEmpty()) return;
        Path target = Files.createDirectories(home.resolve("skills"));
        for (Path skill : skills) {
            Path copy = Files.createDirectories(target.resolve(skill.getFileName().toString()));
            Files.copy(skill.resolve("SKILL.md"), copy.resolve("SKILL.md"));
        }

        SkillStore store = new SkillStore(home);

        assertEquals(skills.size(), store.list().size(), "every bundled skill must be listed");
        for (SkillStore.SkillEntry entry : store.list()) {
            assertTrue(store.load(entry.name()).isPresent(), entry.name() + " did not load");
            assertTrue(!entry.description().equals("(no description)"),
                    entry.name() + " is listed to the model without a description");
        }
    }

    @Test
    void bothPackagersShipTheWholeDirectoryRatherThanNamedSkills() throws Exception {
        String windows = Files.readString(Path.of("scripts", "package-windows.ps1"),
                StandardCharsets.UTF_8);
        String unix = Files.readString(Path.of("scripts", "package-unix.sh"), StandardCharsets.UTF_8);

        // Naming skills one at a time is what let a skill ship on one platform and not the other.
        for (Path skill : bundled()) {
            String name = skill.getFileName().toString();
            assertTrue(!windows.contains(name),
                    "package-windows.ps1 names " + name + "; it should copy the directory");
            assertTrue(!unix.contains(name),
                    "package-unix.sh names " + name + "; it should copy the directory");
        }
        assertTrue(windows.contains(".pironi\\skills"), "the bundle's skills path must be there");
        assertTrue(unix.contains(".pironi/skills"), "the bundle's skills path must be there");
    }
}
