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
}
