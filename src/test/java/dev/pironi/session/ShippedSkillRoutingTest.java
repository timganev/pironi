package dev.pironi.session;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The skills a release ships are one namespace, and adding to it is not additive: two skills that
 * score alike on a question tie, and a tie applies neither. A new skill can therefore switch off a
 * working one, silently, on a question neither of them was written for.
 *
 * <p>These are the questions each shipped skill exists to answer, in both working languages, run
 * against the real skill files rather than against fixtures - because what is being tested is the
 * wording of those files.
 */
class ShippedSkillRoutingTest {
    private SkillStore store;

    @BeforeEach
    void installTheShippedSkills(@TempDir Path home) throws IOException {
        Path source = Path.of("skills");
        assertTrue(Files.isDirectory(source), "the shipped skills should be at " + source.toAbsolutePath());
        Path target = home.resolve("skills");
        Files.createDirectories(target);
        try (Stream<Path> skills = Files.list(source)) {
            for (Path skill : skills.filter(Files::isDirectory).toList()) {
                Path copy = target.resolve(skill.getFileName().toString());
                Files.createDirectories(copy);
                try (Stream<Path> files = Files.list(skill)) {
                    for (Path file : files.filter(Files::isRegularFile).toList()) {
                        Files.copy(file, copy.resolve(file.getFileName().toString()));
                    }
                }
            }
        }
        store = new SkillStore(home);
    }

    @Test
    void everyShippedSkillIsReachedByTheQuestionsItExistsFor() {
        List<String[]> questions = List.of(
                new String[]{"weather-forecast", "дай ми прогноза за времето за три дни"},
                new String[]{"weather-forecast", "what is the forecast for Paris tomorrow"},
                new String[]{"windows-outlook-teams", "извади ми срещите от календара на Outlook"},
                new String[]{"windows-outlook-teams", "read the teams indexeddb store"},
                new String[]{"action-items", "какво решихме и кой за какво е отговорник"},
                new String[]{"action-items", "extract the commitments from this transcript"},
                new String[]{"email-triage", "кое от натрупалите се е спешно"},
                new String[]{"email-triage", "which threads are still unanswered"},
                new String[]{"weekly-reset", "направи седмичния преглед"},
                new String[]{"weekly-reset", "what is my weekly retrospective"}
        );
        for (String[] question : questions) {
            SkillStore.SkillDecision decision = store.decide(question[1]);
            assertEquals(question[0],
                    decision.chosen().map(SkillStore.SkillEntry::name).orElse(null),
                    question[1] + " -> " + decision.reason() + " " + decision.scores());
        }
    }

    @Test
    void ordinaryRequestsReachNoSkillAtAll() {
        // A trigger word that turns up in everyday work applies its skill to everything. "review",
        // "plan" and "преглед" were left out of weekly-reset for exactly this reason.
        List<String> everyday = List.of(
                "review this pull request",
                "прегледай този файл и ми кажи какво прави",
                "plan the migration to Java 25",
                "покажи ми съдържанието на директорията",
                "run the tests and show me what failed",
                "колко време отнема този билд",
                "покажи ми историята на промените"
        );
        for (String request : everyday) {
            SkillStore.SkillDecision decision = store.decide(request);
            assertEquals(java.util.Optional.empty(), decision.chosen().map(SkillStore.SkillEntry::name),
                    request + " -> " + decision.reason() + " " + decision.scores());
        }
    }
}
