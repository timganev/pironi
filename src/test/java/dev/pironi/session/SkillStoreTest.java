package dev.pironi.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

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
}
