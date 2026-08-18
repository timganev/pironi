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

        store.save(workspace, List.of("Outlook.sqlite Mail table is empty", "OSA logs are PII-redacted"));

        assertEquals(
                List.of("Outlook.sqlite Mail table is empty", "OSA logs are PII-redacted"),
                new FindingsStore(home).load(workspace)
        );
    }

    @Test
    void separatesWorkspacesAndKeepsOnlyTheRecentTail(@TempDir Path other) {
        FindingsStore store = new FindingsStore(home);
        store.save(workspace, List.of("only for this workspace"));
        assertEquals(List.of(), store.load(other));

        List<String> many = new ArrayList<>();
        for (int index = 0; index < 50; index++) many.add("fact " + index);
        store.save(other, many);

        List<String> loaded = store.load(other);
        assertEquals(40, loaded.size());
        assertEquals("fact 49", loaded.getLast());
    }
}
