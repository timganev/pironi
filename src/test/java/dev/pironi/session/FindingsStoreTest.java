package dev.pironi.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FindingsStoreTest {
    @TempDir Path home;
    @TempDir Path workspace;

    @Test
    void carriesFindingsIntoTheNextRunOfTheSameWorkspace() {
        FindingsStore store = new FindingsStore(home);
        assertEquals(List.of(), store.load(workspace));

        store.save(workspace, List.of("Outlook.sqlite Mail table is empty", "OSA logs are PII-redacted"),
                "2026-08-20", "2026-08-20T0900-project-1234");

        List<FindingsStore.Finding> loaded = new FindingsStore(home).load(workspace);
        assertEquals(
                List.of("Outlook.sqlite Mail table is empty", "OSA logs are PII-redacted"),
                loaded.stream().map(FindingsStore.Finding::text).toList()
        );
        assertEquals("2026-08-20", loaded.getFirst().date());
        assertEquals("2026-08-20T0900-project-1234", loaded.getFirst().session());
        assertEquals("(2026-08-20) Outlook.sqlite Mail table is empty", loaded.getFirst().forPrompt());
    }

    @Test
    void reconfirmingAFactRefreshesItsDateInsteadOfRepeatingIt() {
        FindingsStore store = new FindingsStore(home);
        store.save(workspace, List.of("the build is Maven"), "2026-07-01", "old-session");

        store.save(workspace, List.of("the build is Maven"), "2026-08-20", "new-session");

        List<FindingsStore.Finding> loaded = store.load(workspace);
        assertEquals(1, loaded.size(), "a fact confirmed again is fresher, not doubled");
        assertEquals("2026-08-20", loaded.getFirst().date());
        assertEquals("new-session", loaded.getFirst().session());
    }

    @Test
    void readsLinesWrittenBeforeDatesExisted() throws Exception {
        // Files written by earlier builds hold bare sentences. They stay readable and simply
        // carry no date, which is honest: nobody recorded when they were true.
        Path file = home.resolve("findings");
        java.nio.file.Files.createDirectories(file);
        FindingsStore store = new FindingsStore(home);
        store.save(workspace, List.of("seed"), "2026-08-20", "s");
        Path stored;
        try (var entries = java.nio.file.Files.list(file)) {
            stored = entries.findFirst().orElseThrow();
        }
        java.nio.file.Files.writeString(stored, "a bare sentence from an older build\n");

        List<FindingsStore.Finding> loaded = store.load(workspace);

        assertEquals("a bare sentence from an older build", loaded.getFirst().text());
        assertEquals("", loaded.getFirst().date());
        assertEquals("a bare sentence from an older build", loaded.getFirst().forPrompt());
    }

    @Test
    void separatesWorkspacesAndKeepsOnlyTheRecentTail(@TempDir Path other) {
        FindingsStore store = new FindingsStore(home);
        store.save(workspace, List.of("only for this workspace"), "2026-08-20", "s");
        assertEquals(List.of(), store.load(other));

        List<String> many = new ArrayList<>();
        for (int index = 0; index < 50; index++) many.add("fact " + index);
        store.save(other, many, "2026-08-20", "s");

        List<FindingsStore.Finding> loaded = store.load(other);
        assertEquals(40, loaded.size());
        assertEquals("fact 49", loaded.getLast().text());
    }

    @Test
    void clearingRemovesTheFile() {
        FindingsStore store = new FindingsStore(home);
        store.save(workspace, List.of("something"), "2026-08-20", "s");

        assertEquals(true, store.clear(workspace));
        assertEquals(List.of(), store.load(workspace));
        assertEquals(false, store.clear(workspace));
    }

    @Test
    void keepsOneFactOnOneLineWhateverTheModelSent() {
        // The record is date, session and text separated by tabs. A newline in the model's
        // sentence would end the line early and the next load would read half of it as a fact.
        FindingsStore store = new FindingsStore(home);

        store.save(workspace, List.of("the build is Maven\nand the tests are JUnit\ttabbed"),
                "2026-08-20", "s");

        List<FindingsStore.Finding> loaded = store.load(workspace);
        assertEquals(1, loaded.size());
        assertEquals("the build is Maven and the tests are JUnit tabbed", loaded.getFirst().text());
    }

    @Test
    void oneDocumentPastedInGrowingPiecesStaysOneRow() {
        FindingsStore store = new FindingsStore(home);
        String document = "=== SOUL.md (who I am) === Grammar and gender: I write in the feminine, "
                + "the user is addressed as male. Identity: Alpha, a personal assistant. "
                + "Operating principles: be genuinely useful, not performatively useful.";

        // One turn each, a little further into the same document every time. Matching on equality
        // alone gave every piece its own row, and the file grew to 37 KB that then rode on every
        // prompt of every later run against this workspace.
        for (int end = 80; end < document.length(); end += 20) {
            store.save(workspace, List.of(document.substring(0, end)), "2026-08-20", "session-1");
        }
        store.save(workspace, List.of(document), "2026-08-20", "session-1");

        List<FindingsStore.Finding> loaded = store.load(workspace);
        assertEquals(1, loaded.size(), "one document is one fact, however far it was written out");
        assertEquals(document, loaded.getFirst().text(), "the fullest wording is the one kept");
    }

    @Test
    void aShorterRestatementDoesNotThrowAwayWhatWasAlreadyKnown() {
        FindingsStore store = new FindingsStore(home);
        String full = "Outlook.sqlite has a Mail table and it holds exactly zero rows, "
                + "and CalendarEvents is empty too";
        store.save(workspace, List.of(full), "2026-08-20", "session-1");

        store.save(workspace, List.of("Outlook.sqlite has a Mail table and it holds exactly zero rows"),
                "2026-08-21", "session-2");

        List<FindingsStore.Finding> loaded = store.load(workspace);
        assertEquals(1, loaded.size());
        assertEquals(full, loaded.getFirst().text());
        assertEquals("2026-08-21", loaded.getFirst().date(), "it was confirmed again today");
    }

    @Test
    void aFilePoisonedBeforeTheRuleHealsOnTheNextWrite() throws Exception {
        FindingsStore store = new FindingsStore(home);
        String document = "=== SOUL.md (who I am) === Grammar and gender: I write in the feminine, "
                + "the user is addressed as male. Identity: Alpha, a personal assistant.";
        store.save(workspace, List.of("a seed fact, long enough to be worth carrying over"),
                "2026-08-20", "old-session");
        Path file;
        try (var entries = java.nio.file.Files.list(home.resolve("findings"))) {
            file = entries.findFirst().orElseThrow();
        }

        // Rewrite it as it stood before the rule: one document at many lengths, a row each.
        List<String> rows = new ArrayList<>();
        for (int end = 60; end < document.length(); end += 20) {
            rows.add("2026-08-20\told-session\t" + document.substring(0, end));
        }
        rows.add("2026-08-20\told-session\t" + document);
        java.nio.file.Files.write(file, rows, java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(rows.size(), store.load(workspace).size(), "the poisoned file is the premise");

        store.save(workspace, List.of("the build is Maven"), "2026-08-22", "new-session");

        assertEquals(
                List.of(document, "the build is Maven"),
                store.load(workspace).stream().map(FindingsStore.Finding::text).toList()
        );
    }
}
