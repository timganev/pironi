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

        String prompt = PersistentAgentMemory.skillForPrompt("windows-outlook-teams", content);

        assertTrue(prompt.contains("cut here"), "a silent cut reads as the whole skill");
        assertTrue(prompt.contains("read_skill"), prompt.substring(prompt.length() - 200));
        assertTrue(prompt.contains("windows-outlook-teams"));
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
}
