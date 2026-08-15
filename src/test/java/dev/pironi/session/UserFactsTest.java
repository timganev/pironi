package dev.pironi.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserFactsTest {
    @TempDir Path home;

    @Test void storesAndListsFactsInOrder() throws Exception {
        UserFacts facts = new UserFacts(home);
        assertEquals("Дневните логове ги искам в logs/", facts.add("Дневните логове ги искам в logs/"));
        assertEquals("Отчетите започват с обобщение", facts.add("Отчетите започват с обобщение"));
        assertEquals(java.util.List.of("Дневните логове ги искам в logs/",
                "Отчетите започват с обобщение"), facts.list());
    }

    @Test void neverTouchesWhatTheUserWroteByHand() throws Exception {
        Files.writeString(home.resolve("USER.md"), """
                # За мен
                Работя като team lead и ползвам Windows.

                ## Предпочитания
                - Пиши кратко.
                """, StandardCharsets.UTF_8);
        new UserFacts(home).add("Логовете в logs/");
        String after = Files.readString(home.resolve("USER.md"), StandardCharsets.UTF_8);
        assertTrue(after.contains("Работя като team lead"), after);
        assertTrue(after.contains("- Пиши кратко."), "hand-written section must survive: " + after);
        assertTrue(after.contains("- Логовете в logs/"), after);
    }

    @Test void rejectsBlankOversizedAndDuplicateFacts() throws Exception {
        UserFacts facts = new UserFacts(home);
        assertEquals("", facts.add("   "));
        assertEquals("", facts.add("x".repeat(500)));
        facts.add("Логовете в logs/");
        assertEquals("", facts.add("логовете в LOGS/"), "duplicates differ only by case");
        assertEquals(1, facts.list().size());
    }

    @Test void forgetsByIndexAndLeavesTheRest() throws Exception {
        UserFacts facts = new UserFacts(home);
        facts.add("първо");
        facts.add("второ");
        facts.add("трето");
        assertEquals("второ", facts.removeAt(2));
        assertEquals(java.util.List.of("първо", "трето"), facts.list());
        assertEquals("", facts.removeAt(9), "out of range must not throw or delete anything");
        assertEquals(2, facts.list().size());
    }

    @Test void redactsSecretsBeforeWritingThemDown() throws Exception {
        UserFacts facts = new UserFacts(home);
        String stored = facts.add("ключът ми е sk-abcdefghijklmnopqrstuvwxyz012345");
        assertFalse(stored.contains("sk-abcdefghijklmnopqrstuvwxyz012345"),
                "a fact is still user text and must go through redaction: " + stored);
        String file = Files.readString(home.resolve("USER.md"), StandardCharsets.UTF_8);
        assertFalse(file.contains("sk-abcdefghijklmnopqrstuvwxyz012345"), file);
    }
}
