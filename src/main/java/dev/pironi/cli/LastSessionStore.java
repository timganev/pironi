package dev.pironi.cli;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

final class LastSessionStore {
    private static final List<String> ORDERED_KEYS = List.of(
            "provider",
            "base-url",
            "model",
            "api-key-env",
            "workspace",
            "approval",
            "max-turns",
            "context",
            "max-output-tokens",
            "timeout-seconds",
            "trace",
            "pironi-home",
            "personal-context",
            "status",
            "verify-command",
            "deny-tools",
            "allow-tools",
            "shell-scope",
            "search-roots"
    );

    private final Path path;

    LastSessionStore(Path path) {
        this.path = path.toAbsolutePath().normalize();
    }

    String[] loadArguments() throws IOException {
        if (!Files.isRegularFile(path)) {
            return new String[0];
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path)) {
            properties.load(reader);
        }
        List<String> arguments = new ArrayList<>();
        for (String key : ORDERED_KEYS) {
            String value = properties.getProperty(key);
            if (value != null && !value.isBlank()) {
                arguments.add("--" + key);
                arguments.add(value);
            }
        }
        return arguments.toArray(String[]::new);
    }

    void save(CliOptions options) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("provider", cliName(options.provider()));
        properties.setProperty("base-url", options.baseUri().toString());
        properties.setProperty("model", options.model());
        properties.setProperty("api-key-env", options.apiKeyEnvironmentName());
        properties.setProperty("workspace", options.workspace().toString());
        properties.setProperty("approval", cliName(options.approvalMode()));
        properties.setProperty("max-turns", Integer.toString(options.maxTurns()));
        properties.setProperty("context", Integer.toString(options.contextSize()));
        properties.setProperty("max-output-tokens", Integer.toString(options.maxOutputTokens()));
        properties.setProperty("timeout-seconds", Long.toString(options.modelTimeout().toSeconds()));
        properties.setProperty("trace", options.tracePath().toString());
        properties.setProperty("pironi-home", options.pironiHome().toString());
        properties.setProperty("personal-context", cliName(options.personalContextMode()));
        properties.setProperty("status", cliName(options.statusMode()));
        if (options.verifyCommand() != null && !options.verifyCommand().isBlank()) {
            properties.setProperty("verify-command", options.verifyCommand());
        }
        if (!options.denyTools().isEmpty()) {
            properties.setProperty(
                    "deny-tools",
                    options.denyTools().stream().sorted().collect(java.util.stream.Collectors.joining(","))
            );
        }
        if (!options.allowTools().isEmpty()) {
            properties.setProperty(
                    "allow-tools",
                    options.allowTools().stream().sorted()
                            .collect(java.util.stream.Collectors.joining(","))
            );
        }
        properties.setProperty("shell-scope", cliName(options.shellScope()));
        properties.setProperty(
                "search-roots",
                options.searchRoots().stream().map(Path::toString)
                        .collect(java.util.stream.Collectors.joining(","))
        );

        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), ".last-session-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary)) {
                properties.store(writer, "Pironi last session; contains no API key or task");
            }
            try {
                Files.move(
                        temporary,
                        path,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String cliName(Enum<?> value) {
        return value.name().toLowerCase().replace('_', '-');
    }
}
