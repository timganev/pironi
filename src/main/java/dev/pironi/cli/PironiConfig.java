package dev.pironi.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Simple key-value config persisted to ~/.pironi/config.properties.
 */
public final class PironiConfig {
    private final Path path;
    private final Properties props;

    public PironiConfig(Path pironiHome) throws IOException {
        this.path = pironiHome.resolve("config.properties");
        this.props = new Properties();
        if (Files.exists(path)) {
            try (var in = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                props.load(in);
            }
        }
    }

    public String get(String key, String defaultValue) {
        return props.getProperty(key, defaultValue);
    }

    public void set(String key, String value) {
        props.setProperty(key, value);
        try {
            Files.createDirectories(path.getParent());
            try (var out = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                props.store(out, "Pironi config");
            }
        } catch (IOException ignored) { }
    }

    public String model() { return get("model", ""); }
    public void model(String m) { set("model", m); }

    public String approval() { return get("approval", "auto"); }
    public void approval(String a) { set("approval", a); }
}
