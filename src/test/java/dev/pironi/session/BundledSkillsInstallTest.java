package dev.pironi.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BundledSkillsInstallTest {
    @TempDir Path seed;
    @TempDir Path store;

    private Path ship(String name, String body) throws Exception {
        Path skill = Files.createDirectories(seed.resolve(name));
        Files.writeString(skill.resolve("SKILL.md"),
                "# " + name + "\n\ndescription: " + body + "\n", StandardCharsets.UTF_8);
        return skill;
    }

    private String stored(String name) throws Exception {
        return Files.readString(store.resolve(name).resolve("SKILL.md"), StandardCharsets.UTF_8);
    }

    @Test
    void whatTheReleaseCarriesIsPlantedOnce() throws Exception {
        ship("team-lead", "first");
        ship("outlook-time", "second");

        assertEquals(List.of("outlook-time", "team-lead"),
                BundledSkills.install(seed, store, "v1"));
        // Second run has nothing to do: planting again would rewrite files nobody asked about.
        assertEquals(List.of(), BundledSkills.install(seed, store, "v1"));
        assertEquals(BundledSkills.Origin.SHIPPED, BundledSkills.originOf(store, "team-lead"));
    }

    @Test
    void aNewerReleaseReplacesWhatNobodyTouched() throws Exception {
        ship("team-lead", "first");
        BundledSkills.install(seed, store, "v1");

        ship("team-lead", "improved");
        assertEquals(List.of("team-lead"), BundledSkills.install(seed, store, "v2"));
        assertTrue(stored("team-lead").contains("improved"));
    }

    @Test
    void anEditMadeHereIsNeverOverwritten() throws Exception {
        ship("team-lead", "first");
        BundledSkills.install(seed, store, "v1");
        Files.writeString(store.resolve("team-lead").resolve("SKILL.md"),
                "# team-lead\n\ndescription: mine now\n", StandardCharsets.UTF_8);

        ship("team-lead", "improved upstream");
        assertEquals(List.of(), BundledSkills.install(seed, store, "v2"));

        assertTrue(stored("team-lead").contains("mine now"), "the edit stands");
        // Still remembered as ours, which is what makes "reset" meaningful later.
        assertEquals(BundledSkills.Origin.SHIPPED_EDITED,
                BundledSkills.originOf(store, "team-lead"));
    }

    @Test
    void aSkillDeletedHereStaysDeleted() throws Exception {
        ship("team-lead", "first");
        BundledSkills.install(seed, store, "v1");

        // Deleting and having it silently return is a program arguing with its user.
        Files.delete(store.resolve("team-lead").resolve("SKILL.md"));
        Files.delete(store.resolve("team-lead"));

        assertEquals(List.of(), BundledSkills.install(seed, store, "v2"));
        assertFalse(Files.exists(store.resolve("team-lead")));
    }

    @Test
    void aSkillWrittenHereUnderTheSameNameIsNotClaimed() throws Exception {
        Path mine = Files.createDirectories(store.resolve("team-lead"));
        Files.writeString(mine.resolve("SKILL.md"),
                "# team-lead\n\ndescription: written here first\n", StandardCharsets.UTF_8);
        ship("team-lead", "from the release");

        assertEquals(List.of(), BundledSkills.install(seed, store, "v1"));

        assertTrue(stored("team-lead").contains("written here first"));
        assertEquals(BundledSkills.Origin.LOCAL, BundledSkills.originOf(store, "team-lead"),
                "a name collision does not make someone else's skill ours");
    }

    @Test
    void aSkillWrittenHereIsItsOwn() throws Exception {
        ship("team-lead", "shipped");
        BundledSkills.install(seed, store, "v1");
        Path mine = Files.createDirectories(store.resolve("my-workflow"));
        Files.writeString(mine.resolve("SKILL.md"), "# my-workflow\n\ndescription: mine\n",
                StandardCharsets.UTF_8);

        assertEquals(BundledSkills.Origin.LOCAL, BundledSkills.originOf(store, "my-workflow"));
        assertEquals(BundledSkills.Origin.SHIPPED, BundledSkills.originOf(store, "team-lead"));
    }

    @Test
    void resetPutsBackTheWordingTheReleaseCarried() throws Exception {
        ship("team-lead", "as shipped");
        BundledSkills.install(seed, store, "v1");
        Files.writeString(store.resolve("team-lead").resolve("SKILL.md"), "# broken\n",
                StandardCharsets.UTF_8);
        assertEquals(BundledSkills.Origin.SHIPPED_EDITED,
                BundledSkills.originOf(store, "team-lead"));

        assertTrue(BundledSkills.reset(seed, store, "team-lead", "v1"));

        assertTrue(stored("team-lead").contains("as shipped"));
        assertEquals(BundledSkills.Origin.SHIPPED, BundledSkills.originOf(store, "team-lead"));
    }

    @Test
    void nothingHappensWithoutABundle() throws Exception {
        assertEquals(List.of(), BundledSkills.install(null, store, "v1"));
        assertEquals(List.of(), BundledSkills.install(seed.resolve("absent"), store, "v1"));
        assertFalse(BundledSkills.reset(null, store, "team-lead", "v1"));
    }

    /**
     * A release that splits or renames a skill plants the new names and leaves the old one behind,
     * where it goes on answering to the same words. Two skills scoring alike on a question tie, and
     * a tie applies neither - so the split that was meant to improve a skill switches it off, on
     * every machine that had the previous release and nowhere else.
     */
    @Test
    void aSkillThisReleaseNoLongerCarriesIsRetired() throws Exception {
        ship("windows-outlook-teams", "the one skill");
        BundledSkills.install(seed, store, "v1");
        assertTrue(Files.isRegularFile(store.resolve("windows-outlook-teams").resolve("SKILL.md")));

        Files.walk(seed.resolve("windows-outlook-teams")).sorted(java.util.Comparator.reverseOrder())
                .forEach(path -> path.toFile().delete());
        ship("windows-outlook", "split in two");
        ship("windows-teams", "split in two");
        BundledSkills.install(seed, store, "v2");

        assertEquals(List.of("windows-outlook-teams"), BundledSkills.retire(seed, store));

        assertFalse(Files.exists(store.resolve("windows-outlook-teams")),
                "the retired skill must stop being listed");
        assertTrue(Files.isRegularFile(
                store.resolve(".archive").resolve("windows-outlook-teams").resolve("SKILL.md")),
                "retiring archives rather than deletes, so restore_skill can bring it back");
        assertEquals(List.of(), BundledSkills.retire(seed, store), "nothing left to retire");
    }

    @Test
    void aSkillTheyEditedIsTheirsAndIsNotRetired() throws Exception {
        ship("windows-outlook-teams", "shipped");
        BundledSkills.install(seed, store, "v1");
        Files.writeString(store.resolve("windows-outlook-teams").resolve("SKILL.md"),
                "# windows-outlook-teams\n\ndescription: my notes\n", StandardCharsets.UTF_8);

        Files.walk(seed.resolve("windows-outlook-teams")).sorted(java.util.Comparator.reverseOrder())
                .forEach(path -> path.toFile().delete());
        ship("windows-outlook", "split in two");

        assertEquals(List.of(), BundledSkills.retire(seed, store));
        assertTrue(stored("windows-outlook-teams").contains("my notes"));
        assertEquals(BundledSkills.Origin.SHIPPED_EDITED,
                BundledSkills.originOf(store, "windows-outlook-teams"));
    }

    @Test
    void anEmptyBundleRetiresNothing() throws Exception {
        ship("team-lead", "shipped");
        BundledSkills.install(seed, store, "v1");
        Files.walk(seed.resolve("team-lead")).sorted(java.util.Comparator.reverseOrder())
                .forEach(path -> path.toFile().delete());

        // A packaging mistake that ships no skills at all must not empty the store: "the release
        // carried nothing" and "the release withdrew everything" are not the same statement.
        assertEquals(List.of(), BundledSkills.retire(seed, store));
        assertTrue(Files.isRegularFile(store.resolve("team-lead").resolve("SKILL.md")));
        assertEquals(List.of(), BundledSkills.retire(null, store));
    }

    @Test
    void aPlantedSkillIsOneTheStoreListsAndTheManifestIsNot() throws Exception {
        ship("team-lead", "shipped");
        Path home = Files.createDirectories(store.resolve("home"));
        BundledSkills.install(seed, home.resolve("skills"), "v1");

        SkillStore skills = new SkillStore(home);

        assertTrue(Files.isRegularFile(home.resolve("skills").resolve(BundledSkills.MANIFEST)),
                "the manifest is written where the skills are");
        assertEquals(List.of("team-lead"), skills.list().stream()
                .map(SkillStore.SkillEntry::name).toList());
        assertEquals(BundledSkills.Origin.SHIPPED, skills.origin("team-lead"));
    }
}
