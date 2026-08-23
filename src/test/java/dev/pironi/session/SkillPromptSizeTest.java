package dev.pironi.session;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The chosen skill is the one thing a task is given beyond its own words, and it used to be cut at
 * 8,000 characters in silence. The largest shipped skill is 21,903, so two thirds of it had never
 * reached a model: the notes written before the cut changed behaviour the same evening, and the
 * ones written after it did nothing at all and looked like the model ignoring instructions.
 */
class SkillPromptSizeTest {

    @Test
    void aSkillThatFitsArrivesWhole() {
        String content = "x".repeat(PersistentAgentMemory.MAX_SKILL_PROMPT_CHARACTERS);

        assertEquals(content, PersistentAgentMemory.skillForPrompt("big", content));
    }

    @Test
    void aSkillThatDoesNotFitSaysSoAndSaysWhereTheRestIs() {
        String content = "x".repeat(PersistentAgentMemory.MAX_SKILL_PROMPT_CHARACTERS + 500);

        String prompt = PersistentAgentMemory.skillForPrompt("windows-outlook", content);

        assertTrue(prompt.contains("cut here"), "a silent cut reads as the whole skill");
        assertTrue(prompt.contains("read_skill"), prompt.substring(prompt.length() - 200));
        assertTrue(prompt.contains("windows-outlook"));
    }

    @Test
    void everyShippedSkillReachesTheModelWhole() throws IOException {
        Path skills = Path.of("skills");
        if (!Files.isDirectory(skills)) return;
        StringBuilder tooBig = new StringBuilder();
        try (Stream<Path> entries = Files.list(skills)) {
            for (Path skill : entries.filter(Files::isDirectory).sorted().toList()) {
                Path md = skill.resolve("SKILL.md");
                if (!Files.isRegularFile(md)) continue;
                int size = Files.readString(md, StandardCharsets.UTF_8).length();
                if (size > PersistentAgentMemory.MAX_SKILL_PROMPT_CHARACTERS) {
                    tooBig.append("\n  ").append(skill.getFileName())
                            .append(": ").append(size).append(" characters");
                }
            }
        }
        assertEquals("", tooBig.toString(),
                "these skills would be cut before the model sees the end of them:" + tooBig);
    }

    /**
     * The cap is a cliff, not a slope: past it a skill stops being listed, and nothing says so -
     * the skill simply stops being offered and the run reads as one where it never existed. The
     * largest shipped skill sat 2,097 bytes from that edge, which in Cyrillic is about twenty
     * lines, while being actively added to every night.
     *
     * <p>Failing at nine tenths leaves the 2,400 characters that a sitting's worth of notes needs,
     * so this fires while splitting the file is still a decision rather than a repair.
     */
    @Test
    void noShippedSkillIsWithinOneSittingOfTheCliff() throws IOException {
        int warnAt = PersistentAgentMemory.MAX_SKILL_PROMPT_CHARACTERS * 9 / 10;
        Path skills = Path.of("skills");
        if (!Files.isDirectory(skills)) return;
        StringBuilder crowded = new StringBuilder();
        try (Stream<Path> entries = Files.list(skills)) {
            for (Path skill : entries.filter(Files::isDirectory).sorted().toList()) {
                Path md = skill.resolve("SKILL.md");
                if (!Files.isRegularFile(md)) continue;
                int size = Files.readString(md, StandardCharsets.UTF_8).length();
                if (size > warnAt) {
                    crowded.append("\n  ").append(skill.getFileName()).append(": ").append(size)
                            .append(" of ").append(PersistentAgentMemory.MAX_SKILL_PROMPT_CHARACTERS)
                            .append(" characters, ")
                            .append(PersistentAgentMemory.MAX_SKILL_PROMPT_CHARACTERS - size)
                            .append(" left");
                }
            }
        }
        assertEquals("", crowded.toString(),
                "split these before they reach the cap, rather than after:" + crowded);
    }

    /**
     * Listing weighed bytes while the cap counts characters. A skill written in Cyrillic costs two
     * bytes a character, so it could pass every other check and still be dropped from the catalog.
     */
    @Test
    void aSkillIsMeasuredInCharactersEverywhereItIsMeasured(@org.junit.jupiter.api.io.TempDir Path home)
            throws IOException {
        Path skill = home.resolve("skills").resolve("cyrillic");
        Files.createDirectories(skill);
        String body = "я".repeat(PersistentAgentMemory.MAX_SKILL_PROMPT_CHARACTERS - 100);
        Files.writeString(skill.resolve("SKILL.md"),
                "# cyrillic\n\ndescription: two bytes a character\ntriggers: cyrillic\n\n" + body,
                StandardCharsets.UTF_8);
        assertTrue(Files.size(skill.resolve("SKILL.md")) > PersistentAgentMemory.MAX_SKILL_PROMPT_CHARACTERS,
                "the fixture has to be over the cap in bytes for this to test anything");

        SkillStore store = new SkillStore(home);

        assertFalse(store.list().isEmpty(), "a skill inside the cap must still be listed");
        assertTrue(store.load("cyrillic").isPresent());
    }
}
