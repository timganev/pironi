package dev.pironi.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.*;

class SkillStoreTest {
    @TempDir Path temporaryDirectory;

    @Test void savesListsLoadsArchivesAndRestores() throws Exception {
        SkillStore store = new SkillStore(temporaryDirectory);
        assertTrue(store.save("java-review", "---\ndescription: \"Review Java\"\n---\nBody"));
        assertEquals("java-review", store.list().getFirst().name());
        assertEquals("Review Java", store.list().getFirst().description());
        assertTrue(store.loadIndex().contains("java-review"));
        assertTrue(store.load("java-review").orElseThrow().contains("Body"));
        assertTrue(store.archive("java-review"));
        assertTrue(store.list().isEmpty());
        assertTrue(store.restore("java-review"));
        assertFalse(store.load("../../escape").isPresent());
        assertFalse(store.archive("../../escape"));
    }

    @Test void rejectsEmptySkills() throws Exception {
        SkillStore store = new SkillStore(temporaryDirectory);
        assertFalse(store.save("", "body"));
        assertFalse(store.save("name", ""));
        assertFalse(store.save("huge", "x".repeat(24_001)));
    }

    @Test void redactsSecretsBeforePersistingSkill() throws Exception {
        SkillStore store = new SkillStore(temporaryDirectory);
        assertTrue(store.save("safe-process", """
                ---
                description: Safe process
                ---
                Use DEEPSEEK_API_KEY=never-store-this and password: also-secret.
                """));

        String content = store.load("safe-process").orElseThrow();
        assertFalse(content.contains("never-store-this"));
        assertFalse(content.contains("also-secret"));
        assertTrue(content.contains("[REDACTED]"));
    }

    @Test void promptIndexStaysBoundedWhenManySkillsExist() throws Exception {
        SkillStore store = new SkillStore(temporaryDirectory);
        for (int index = 0; index < 100; index++) {
            assertTrue(store.save("skill-" + index, """
                    ---
                    description: A reusable business workflow with enough descriptive text
                    ---
                    Body
                    """));
        }

        String promptIndex = store.loadIndex();
        assertTrue(promptIndex.length() <= 2_400);
        assertTrue(promptIndex.contains("Pironi Skills"));
        assertTrue(promptIndex.lines().count() <= 27);
    }

    @Test void createDoesNotSilentlyOverwriteExistingSkill() throws Exception {
        SkillStore store = new SkillStore(temporaryDirectory);
        assertTrue(store.save("workflow", "---\ndescription: Original\n---\nOriginal body"));

        assertFalse(store.save(
                "workflow", "---\ndescription: Replacement\n---\nReplacement body"
        ));
        String saved = store.load("workflow").orElseThrow();
        assertTrue(saved.contains("Original body"));
        assertFalse(saved.contains("Replacement body"));
    }

    @Test void readingSkillDoesNotMutateItsModificationTime() throws Exception {
        SkillStore store = new SkillStore(temporaryDirectory);
        assertTrue(store.save("workflow", "---\ndescription: Original\n---\nBody"));
        Path skill = temporaryDirectory.resolve("skills/workflow/SKILL.md");
        FileTime original = FileTime.fromMillis(1_700_000_000_000L);
        Files.setLastModifiedTime(skill, original);

        assertTrue(store.load("workflow").isPresent());

        assertEquals(original, Files.getLastModifiedTime(skill));
    }

    @Test void unloadableOversizedSkillIsAbsentFromListAndPromptIndex() throws Exception {
        SkillStore store = new SkillStore(temporaryDirectory);
        Path directory = temporaryDirectory.resolve("skills/oversized");
        Files.createDirectories(directory);
        Files.writeString(
                directory.resolve("SKILL.md"),
                "---\ndescription: Oversized\n---\n" + "x".repeat(24_001)
        );

        assertTrue(store.list().stream().noneMatch(skill -> skill.name().equals("oversized")));
        assertFalse(store.loadIndex().contains("oversized"));
    }

    @Test void relevanceReturnsOneStrongMatchButNoIrrelevantOrTiedSkill() throws Exception {
        SkillStore store = new SkillStore(temporaryDirectory);
        assertTrue(store.save("weekly-status", """
                ---
                description: Prepare weekly team status report with owners and blockers
                ---
                Body
                """));
        assertTrue(store.save("invoice-review", """
                ---
                description: Review supplier invoices and payment totals
                ---
                Body
                """));

        assertEquals("weekly-status", store.findRelevant(
                "Prepare the weekly status report with owners"
        ).orElseThrow().name());
        assertTrue(store.findRelevant("Weather tomorrow in Sofia").isEmpty());

        // The name carries weight now, so a copy has to be named as close to the request as
        // the original is - otherwise the better-named one wins outright, which is the point.
        assertTrue(store.save("weekly-status-copy", """
                ---
                description: Prepare weekly team status report with owners and blockers
                ---
                Body
                """));
        assertTrue(store.findRelevant(
                "Prepare the weekly status report with owners"
        ).isEmpty(), "equal top scores must not select an arbitrary skill");
    }

    @Test void aThousandSkillsAreScoredOnMetadataAndNeverOnTheirBodies() throws Exception {
        // This asserted a wall-clock budget and failed on a loaded machine while passing alone -
        // measuring the runner rather than the code, which is what took two releases down on the
        // Windows runner. The property it was reaching for is that scoring reads the frontmatter
        // and not the body, and that can be proved outright: a body stuffed with the query must
        // lose to a description that matches it.
        SkillStore store = new SkillStore(temporaryDirectory);
        Path root = temporaryDirectory.resolve("skills");
        String query = "Reconcile quarterly supplier invoices and disputed totals";
        for (int index = 0; index < 1_000; index++) {
            Path directory = Files.createDirectories(root.resolve("workflow-" + index));
            Files.writeString(directory.resolve("SKILL.md"), """
                    ---
                    description: Generic archived business workflow %d
                    ---
                    Body that is never loaded during metadata scoring.
                    """.formatted(index));
        }
        Files.writeString(root.resolve("workflow-777/SKILL.md"), """
                ---
                description: Reconcile quarterly supplier invoices and disputed totals
                ---
                Correct workflow.
                """);
        Files.writeString(root.resolve("workflow-42/SKILL.md"), """
                ---
                description: Generic archived business workflow 42
                ---
                %s %s %s
                """.formatted(query, query, query));

        var relevant = store.findRelevant(query);

        assertEquals("workflow-777", relevant.orElseThrow().name());
        assertTrue(store.loadIndex().length() <= 2_400);
    }

    @Test void replacementRequiresExpectedHashAndArchivesPreviousVersion() throws Exception {
        SkillStore store = new SkillStore(temporaryDirectory);
        assertTrue(store.save("workflow", "---\ndescription: Original\n---\nOriginal body"));
        String expected = store.contentHash("workflow").orElseThrow();

        assertFalse(store.replace(
                "workflow", "0".repeat(64), "---\ndescription: New\n---\nNew body"
        ));
        assertTrue(store.load("workflow").orElseThrow().contains("Original body"));
        assertTrue(store.replace(
                "workflow", expected, "---\ndescription: New\n---\nNew body"
        ));
        assertTrue(store.load("workflow").orElseThrow().contains("New body"));
        assertTrue(Files.walk(temporaryDirectory.resolve("skills/.archive/versions"))
                .filter(path -> path.getFileName().toString().equals("SKILL.md"))
                .anyMatch(path -> {
                    try {
                        return Files.readString(path).contains("Original body");
                    } catch (Exception e) {
                        return false;
                    }
                }));
    }

    @Test void relevanceUsesTriggersAndHonorsExclusions() throws Exception {
        SkillStore store = new SkillStore(temporaryDirectory);
        assertTrue(store.save("reporting", """
                ---
                description: Produce the approved report
                triggers: "петъчен отчет | екипен напредък"
                exclusions: "еднократен инцидент"
                ---
                Body
                """));

        assertEquals("reporting", store.findRelevant(
                "Подготви петъчен отчет за екипен напредък"
        ).orElseThrow().name());
        assertTrue(store.findRelevant(
                "Подготви петъчен отчет за еднократен инцидент"
        ).isEmpty());
    }
}
