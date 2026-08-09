package dev.pironi.cli;

import dev.pironi.status.ThemeSettings;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

final class ThemeStore {
    private final Path path;

    ThemeStore(Path pironiHome) { this.path = pironiHome.resolve("theme.properties"); }

    ThemeSettings load() {
        ThemeSettings theme = new ThemeSettings();
        if (!Files.isRegularFile(path)) return theme;
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path)) {
            properties.load(reader);
            for (ThemeSettings.Element element : ThemeSettings.Element.values()) {
                String value = properties.getProperty(element.name().toLowerCase());
                if (value != null) theme.color(element, Integer.parseInt(value));
            }
        } catch (IOException | NumberFormatException ignored) {
            theme.reset();
        }
        return theme;
    }

    void save(ThemeSettings theme) throws IOException {
        Properties properties = new Properties();
        for (ThemeSettings.Element element : ThemeSettings.Element.values()) {
            properties.setProperty(element.name().toLowerCase(),
                    Integer.toString(theme.color(element)));
        }
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), ".theme-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary)) {
                properties.store(writer, "Pironi terminal colors");
            }
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
