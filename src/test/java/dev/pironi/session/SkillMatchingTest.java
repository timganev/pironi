package dev.pironi.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillMatchingTest {
    @TempDir Path home;

    private SkillStore storeWith(String name, String description, String triggers) throws IOException {
        SkillStore store = new SkillStore(home);
        Path dir = home.resolve("skills").resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), """
                ---
                name: %s
                description: "%s"
                triggers: "%s"
                ---

                # %s

                ## Steps
                1. Do the thing.
                """.formatted(name, description, triggers, name), StandardCharsets.UTF_8);
        return store;
    }

    @Test void bulgarianInflectionsStillMatchTheTrigger() throws Exception {
        SkillStore store = storeWith("weekly-status",
                "Седмичен статус пакет за екипа",
                "седмичен статус пакет | седмичен отчет");

        // The user does not repeat the trigger verbatim the second time.
        assertTrue(store.findRelevant("направи ми седмичния статус пакет").isPresent(),
                "definite article must not break the match");
        assertTrue(store.findRelevant("искам седмичните статуси").isPresent(),
                "plural must not break the match");
        assertTrue(store.findRelevant("подготви отчета за седмицата").isPresent(),
                "inflected synonym from the trigger list must still match");
    }

    @Test void unrelatedTaskStillDoesNotMatch() throws Exception {
        SkillStore store = storeWith("weekly-status",
                "Седмичен статус пакет за екипа",
                "седмичен статус пакет");
        assertTrue(store.findRelevant("компилирай проекта и пусни тестовете").isEmpty(),
                "stemming must not make everything match");
    }

    @Test void englishInflectionsMatchToo() throws Exception {
        SkillStore store = storeWith("release-notes",
                "Prepare release notes from commits",
                "release notes | changelog");
        assertTrue(store.findRelevant("prepare the release notes").isPresent());
        assertTrue(store.findRelevant("preparing releases and notes").isPresent());
    }

    @Test void narrowerSkillWinsWhenHitsAreEqual() throws Exception {
        SkillStore store = new SkillStore(home);
        Path narrow = home.resolve("skills").resolve("weekly-status");
        Files.createDirectories(narrow);
        Files.writeString(narrow.resolve("SKILL.md"), """
                ---
                name: weekly-status
                description: "седмичен статус"
                triggers: "седмичен статус"
                ---
                # weekly-status
                """, StandardCharsets.UTF_8);
        Path broad = home.resolve("skills").resolve("everything");
        Files.createDirectories(broad);
        Files.writeString(broad.resolve("SKILL.md"), """
                ---
                name: everything
                description: "седмичен статус отчет доклад таблица презентация календар среща имейл проект"
                triggers: "седмичен статус отчет доклад таблица презентация календар среща имейл"
                ---
                # everything
                """, StandardCharsets.UTF_8);

        // Both match the same two words; the specific skill is the useful answer,
        // where previously the tie meant no skill was applied at all.
        assertEquals("weekly-status",
                store.findRelevant("седмичен статус").map(SkillStore.SkillEntry::name).orElse("(none)"));
    }

    @Test void relatedFormsAcceptsInflectionsAndRejectsLookalikes() {
        assertTrue(SkillStore.relatedForms("отчет", "отчета"));
        assertTrue(SkillStore.relatedForms("седмичен", "седмичния"));
        // Consonant change that a suffix stripper cannot bridge.
        assertTrue(SkillStore.relatedForms("седмица", "седмичен"));
        assertTrue(SkillStore.relatedForms("prepare", "preparing"));
        assertTrue(SkillStore.relatedForms("release", "releases"));

        assertFalse(SkillStore.relatedForms("тест", "текст"));
        assertFalse(SkillStore.relatedForms("файл", "фабрика"));
        assertFalse(SkillStore.relatedForms("код", "команда"));
    }

    @Test void decisionExplainsWhyASkillWasChosen() throws Exception {
        SkillStore store = storeWith("weekly-status",
                "Седмичен статус пакет", "седмичен статус пакет");
        var decision = store.decide("направи ми седмичния статус");
        assertEquals("chosen", decision.reason());
        assertEquals("weekly-status", decision.chosen().orElseThrow().name());
        assertTrue(decision.scores().stream().anyMatch(s -> s.startsWith("weekly-status=")),
                "every considered skill must appear with its score: " + decision.scores());
    }

    @Test void decisionExplainsWhyNoSkillWasChosen() throws Exception {
        SkillStore store = storeWith("weekly-status",
                "Седмичен статус пакет", "седмичен статус пакет");
        var decision = store.decide("компилирай проекта и пусни тестовете");
        assertTrue(decision.chosen().isEmpty());
        // The user can now see the skill was considered and simply did not match,
        // rather than wondering whether it was loaded at all.
        assertTrue(java.util.List.of("no-match", "below-threshold").contains(decision.reason()),
                "unexpected reason: " + decision.reason());
        assertFalse(decision.scores().isEmpty(), "the skill was still evaluated");
    }

    @Test void decisionReportsATieRatherThanPickingArbitrarily() throws Exception {
        SkillStore store = new SkillStore(home);
        for (String name : new String[]{"status-a", "status-b"}) {
            Path dir = home.resolve("skills").resolve(name);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("SKILL.md"), """
                    ---
                    name: %s
                    description: "седмичен статус пакет"
                    triggers: "седмичен статус пакет"
                    ---
                    # %s
                    """.formatted(name, name), StandardCharsets.UTF_8);
        }
        var decision = store.decide("седмичен статус пакет");
        assertEquals("tie", decision.reason());
        assertTrue(decision.chosen().isEmpty());
        assertEquals(2, decision.scores().size());
    }

    @Test
    void anExcludedSkillSaysSoInsteadOfVanishing() throws Exception {
        // A model wrote its exclusions as behaviour instructions - "do not fetch more than the
        // 3-day forecast" - so the skill excluded itself on its own subject. Dropped from the
        // scores entirely, that looked exactly like a skill that had never been saved.
        SkillStore skills = new SkillStore(home);
        skills.save("top10", """
                ---
                description: "Three-day weather forecast for the ten largest cities"
                triggers: "weather forecast for top 10 cities"
                exclusions: "Do not fetch more than the required 3-day forecast."
                ---
                Fetch each city.
                """);

        var decision = skills.decide("What is the 3-day weather forecast for the largest cities?");

        assertTrue(decision.chosen().isEmpty());
        assertTrue(decision.scores().contains("top10=excluded"), decision.scores().toString());
    }
}
