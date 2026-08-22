package dev.pironi.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Starting with no arguments replays the last run's, which is a convenience until it replays where
 * the memory lives. A single --portable, or a one-off --pironi-home, used to pin every later start
 * to that directory with nothing said - including one that had since been deleted, which Workspace
 * would then quietly create again.
 */
class LastSessionHomeTest {
    @TempDir Path home;

    private LastSessionStore store() {
        return new LastSessionStore(home.resolve("last-session.properties"));
    }

    private CliOptions options(String... extra) {
        String[] head = {"--provider", "ollama", "--model", "qwen3.6:35b-a3b",
                "--workspace", home.toString()};
        String[] all = new String[head.length + extra.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(extra, 0, all, head.length, extra.length);
        return CliOptions.parse(all, java.util.Map.of());
    }

    @Test
    void whereTheMemoryLivesIsNotRememberedByTheMemory() throws Exception {
        Path elsewhere = Files.createDirectories(home.resolve("portable").resolve(".pironi"));

        store().save(options("--pironi-home", elsewhere.toString()));

        String written = Files.readString(home.resolve("last-session.properties"),
                StandardCharsets.UTF_8);
        assertFalse(written.contains("pironi-home"), written);
        assertFalse(List.of(store().loadArguments()).contains("--pironi-home"));
    }

    @Test
    void everythingElseIsStillReplayed() throws Exception {
        store().save(options("--approval", "ask"));

        List<String> replayed = List.of(store().loadArguments());

        assertTrue(replayed.contains("--model"));
        assertTrue(replayed.contains("qwen3.6:35b-a3b"));
        assertTrue(replayed.contains("--approval"));
        assertTrue(replayed.contains("ask"));
    }

    @Test
    void aWorkspaceThatIsGoneIsNotReplayedIntoExistence() throws Exception {
        Path gone = Files.createDirectories(home.resolve("scratch"));
        store().save(CliOptions.parse(new String[]{"--provider", "ollama",
                "--model", "qwen3.6:35b-a3b", "--workspace", gone.toString()}, java.util.Map.of()));
        Files.delete(gone);

        List<String> replayed = List.of(store().loadArguments());

        // Workspace creates a missing directory, so replaying this would rebuild a folder the
        // person deleted and work inside it - a failure that looks like success.
        assertFalse(replayed.contains("--workspace"), replayed.toString());
        assertTrue(replayed.contains("--model"), "the rest of the run is still remembered");
    }

    @Test
    void aWorkspaceThatIsStillThereIsReplayed() throws Exception {
        store().save(options());

        assertTrue(List.of(store().loadArguments()).contains("--workspace"));
    }

    @Test
    void theBannerNamesAHomeThatIsNotTheUsualOne() {
        Path ordinary = Path.of(System.getProperty("user.home"), ".pironi");

        assertFalse(PironiMain.sessionBanner("s1", ordinary).contains("home:"),
                "the ordinary home is not worth a word");
        String moved = PironiMain.sessionBanner("s1", home.resolve(".pironi"));
        assertTrue(moved.contains("home: "), moved);
        assertTrue(moved.contains(home.resolve(".pironi").toString()), moved);
        // Whatever else changes, the banner still has to say how to come back to this session.
        assertTrue(moved.contains("/resume s1"), moved);
    }

    @Test
    void aBannerWithoutAHomeStillReads() {
        assertEquals(PironiMain.sessionBanner("s1"),
                PironiMain.sessionBanner("s1", Path.of(System.getProperty("user.home"), ".pironi")));
        assertTrue(PironiMain.sessionBanner("s1", null).contains("/resume s1"));
    }
}
