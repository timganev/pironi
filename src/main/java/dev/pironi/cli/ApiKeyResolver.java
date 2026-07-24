package dev.pironi.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

final class ApiKeyResolver {
    private ApiKeyResolver() {
    }

    static String resolve(
            Map<String, String> environment,
            String variableName,
            Path dotenvFallback
    ) {
        String environmentValue = environment.get(variableName);
        if (environmentValue != null && !environmentValue.isBlank()) {
            return environmentValue;
        }
        if (dotenvFallback == null || !Files.exists(dotenvFallback)) {
            return null;
        }
        if (!Files.isRegularFile(dotenvFallback)) {
            throw new IllegalArgumentException(
                    "API key fallback is not a regular file: " + dotenvFallback
            );
        }

        try {
            for (String line : Files.readAllLines(dotenvFallback, StandardCharsets.UTF_8)) {
                String value = valueFor(line, variableName);
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
            return null;
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "Cannot read API key fallback " + dotenvFallback + ": " + e.getMessage(),
                    e
            );
        }
    }

    private static String valueFor(String line, String variableName) {
        String candidate = line.strip();
        if (candidate.startsWith("export ")) {
            candidate = candidate.substring("export ".length()).stripLeading();
        }
        int separator = candidate.indexOf('=');
        if (separator < 0 || !candidate.substring(0, separator).strip().equals(variableName)) {
            return null;
        }
        String value = candidate.substring(separator + 1).strip();
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                value = value.substring(1, value.length() - 1);
            }
        }
        return value;
    }
}
