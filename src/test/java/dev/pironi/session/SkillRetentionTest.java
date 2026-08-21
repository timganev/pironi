package dev.pironi.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;

class SkillRetentionTest {
    @TempDir Path temporaryDirectory;

    private SkillStore storeWith(String... names) throws Exception {
        SkillStore store = new SkillStore(temporaryDirectory);
        for (String name : names) {
            store.save(name, "---\ndescription: \"d\"\n---\n\n# " + name + "\n\n## Steps\n1. Do it\n");
        }
        return store;
    }

    private Path skillDir(String name) {
        return temporaryDirectory.resolve("skills").resolve(name);
    }

    private void usedDaysAgo(String name, int days) throws Exception {
        Files.writeString(skillDir(name).resolve(".used"),
                Instant.now().minus(days, ChronoUnit.DAYS).toString(), StandardCharsets.UTF_8);
    }

    @Test void aSkillUnusedPastTheWindowIsArchived() throws Exception {
        SkillStore store = storeWith("stale");
        usedDaysAgo("stale", 61);

        assertEquals(1, store.pruneStale(60));
        assertFalse(store.exists("stale"));
        assertTrue(store.listArchived().contains("stale"));
    }

    @Test void aSkillUsedInsideTheWindowStays() throws Exception {
        SkillStore store = storeWith("fresh");
        usedDaysAgo("fresh", 59);

        assertEquals(0, store.pruneStale(60));
        assertTrue(store.exists("fresh"));
    }

    /**
     * Staleness used to read SKILL.md's modification time. A skill applied daily but never edited
     * looked untouched, so the mechanism would have archived exactly the skills that were working.
     */
    @Test void useCountsEvenWhenTheSkillIsNeverEdited() throws Exception {
        SkillStore store = storeWith("daily");
        Files.setLastModifiedTime(skillDir("daily").resolve("SKILL.md"),
                java.nio.file.attribute.FileTime.from(
                        Instant.now().minus(400, ChronoUnit.DAYS)));
        store.markUsed("daily");

        assertEquals(0, store.pruneStale(60));
        assertTrue(store.exists("daily"));
    }

    /** With no stamp yet, a skill is as old as its file - not brand new, and not instantly stale. */
    @Test void anUnstampedSkillFallsBackToWhenItWasWritten() throws Exception {
        SkillStore store = storeWith("written-long-ago");
        Files.deleteIfExists(skillDir("written-long-ago").resolve(".used"));
        Files.setLastModifiedTime(skillDir("written-long-ago").resolve("SKILL.md"),
                java.nio.file.attribute.FileTime.from(Instant.now().minus(90, ChronoUnit.DAYS)));

        assertEquals(1, store.pruneStale(60));
    }

    /** Restoring a skill archived for going unused must not hand it to the very next prune. */
    @Test void restoringRefreshesTheUsageStamp() throws Exception {
        SkillStore store = storeWith("revived");
        usedDaysAgo("revived", 61);
        store.pruneStale(60);

        assertTrue(store.restore("revived"));
        assertEquals(0, store.pruneStale(60));
        assertTrue(store.exists("revived"));
    }

    @Test void anArchivedSkillIsDeletedForGoodOnceItsWindowPasses() throws Exception {
        SkillStore store = storeWith("gone");
        store.archive("gone");
        Files.setLastModifiedTime(
                temporaryDirectory.resolve("skills").resolve(".archive").resolve("gone"),
                java.nio.file.attribute.FileTime.from(Instant.now().minus(31, ChronoUnit.DAYS)));

        assertEquals(1, store.purgeArchived(30));
        assertFalse(store.listArchived().contains("gone"));
        assertFalse(store.restore("gone"));
    }

    @Test void aRecentlyArchivedSkillIsStillRecoverable() throws Exception {
        SkillStore store = storeWith("recent");
        store.archive("recent");

        assertEquals(0, store.purgeArchived(30));
        assertTrue(store.restore("recent"));
        assertTrue(store.exists("recent"));
    }
}
