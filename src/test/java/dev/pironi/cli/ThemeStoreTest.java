package dev.pironi.cli;

import dev.pironi.status.ThemeSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ThemeStoreTest {
    @TempDir Path temporaryDirectory;

    @Test void savesAndLoadsAllColorsWithoutSecrets() throws Exception {
        ThemeStore store = new ThemeStore(temporaryDirectory);
        ThemeSettings theme = new ThemeSettings();
        theme.color(ThemeSettings.Element.USER, 208);
        theme.color(ThemeSettings.Element.AGENT, 5);
        theme.color(ThemeSettings.Element.ACTIVITY, 6);
        theme.color(ThemeSettings.Element.SYSTEM, 8);
        theme.color(ThemeSettings.Element.ERROR, 1);

        store.save(theme);
        ThemeSettings restored = store.load();

        for (ThemeSettings.Element element : ThemeSettings.Element.values()) {
            assertEquals(theme.color(element), restored.color(element));
        }
        String persisted = Files.readString(temporaryDirectory.resolve("theme.properties"));
        assertFalse(persisted.toLowerCase().contains("password"));
        assertFalse(persisted.toLowerCase().contains("api_key"));
    }

    @Test void corruptFileFallsBackToDefaults() throws Exception {
        Files.writeString(temporaryDirectory.resolve("theme.properties"), "agent=broken\n");
        ThemeSettings restored = new ThemeStore(temporaryDirectory).load();
        assertEquals(2, restored.color(ThemeSettings.Element.AGENT));
        assertEquals(6, restored.color(ThemeSettings.Element.USER));
    }
}
