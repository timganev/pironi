package dev.pironi.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LastSessionStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void roundTripRestoresSettingsWithoutTaskOrSecret() throws Exception {
        Path stateFile = temporaryDirectory.resolve("home/last-session.properties");
        LastSessionStore store = new LastSessionStore(stateFile);
        CliOptions original = CliOptions.parse(
                new String[]{
                        "--provider", "deepseek",
                        "--model", "deepseek-v4-pro",
                        "--workspace", temporaryDirectory.toString(),
                        "--approval", "ask",
                        "--context", "12345",
                        "--task", "do not persist me"
                },
                Map.of("DEEPSEEK_API_KEY", "secret"),
                temporaryDirectory.resolve("missing.env")
        );

        store.save(original);

        String contents = Files.readString(stateFile);
        assertFalse(contents.contains("secret"));
        assertFalse(contents.contains("do not persist me"));

        CliOptions restored = CliOptions.parse(
                store.loadArguments(),
                Map.of("DEEPSEEK_API_KEY", "new-secret"),
                temporaryDirectory.resolve("missing.env")
        );
        assertEquals(original.provider(), restored.provider());
        assertEquals(original.model(), restored.model());
        assertEquals(original.workspace(), restored.workspace());
        assertEquals(original.approvalMode(), restored.approvalMode());
        assertEquals(original.contextSize(), restored.contextSize());
        assertTrue(restored.interactive());
        assertEquals(null, restored.task());
        assertEquals("new-secret", restored.apiKey());
    }
}
