package dev.pironi.status;

import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** Produces short, non-sensitive descriptions of observable tool activity. */
final class ToolActivityFormatter {
    private static final int MAX_VALUE = 140;

    private ToolActivityFormatter() {
    }

    static String started(String tool, JsonNode arguments) {
        String path = safeText(arguments, "path");
        return switch (tool) {
            case "read_file" -> "Reading " + fallback(path, "a file");
            case "write_file" -> "Writing " + fallback(path, "a file");
            case "apply_patch" -> "Editing " + fallback(path, "a file") + " with apply_patch";
            case "move_file" -> "Moving " + fallback(safeText(arguments, "source"), "a file")
                    + " to " + fallback(safeText(arguments, "destination"), "another path");
            case "list_files" -> "Listing " + fallback(path, "workspace files");
            case "find_files" -> "Searching files under " + fallback(path, "the workspace");
            case "inspect_file" -> "Inspecting " + fallback(path, "a file");
            case "http_get" -> "Fetching " + safeUrl(safeText(arguments, "url"));
            case "network_speed" -> "Measuring network latency and download speed";
            case "run_command" -> summarizeCommand(safeText(arguments, "command"));
            case "propose_skill" -> "Preparing skill draft "
                    + fallback(safeText(arguments, "name"), "(unnamed)");
            case "rollback_checkpoint" -> "Rolling back the latest file checkpoint";
            case "system_info" -> "Inspecting runtime capabilities";
            default -> "Running tool " + safeIdentifier(tool);
        };
    }

    static String finished(String tool, boolean success, long durationMillis) {
        return (success ? "Completed " : "Failed ") + safeIdentifier(tool)
                + " in " + durationMillis + " ms";
    }

    private static String summarizeCommand(String command) {
        if (command.isBlank()) return "Running a command";
        String trimmed = command.strip();
        String executable = trimmed.split("\\s+", 2)[0];
        String lower = executable.toLowerCase(Locale.ROOT);
        if (lower.endsWith("curl") || lower.endsWith("curl.exe")) {
            for (String part : trimmed.split("\\s+")) {
                if (part.startsWith("http://") || part.startsWith("https://")) {
                    return "Running " + safeIdentifier(executable) + " " + safeUrl(part);
                }
            }
        }
        return "Running command " + safeIdentifier(executable);
    }

    private static String safeUrl(String value) {
        if (value.isBlank()) return "a URL";
        try {
            URI uri = new URI(value);
            if (uri.getScheme() == null || uri.getHost() == null) return "a URL";
            URI safe = new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(),
                    uri.getPath(), null, null);
            return truncate(safe.toString());
        } catch (URISyntaxException e) {
            return "a URL";
        }
    }

    private static String safeText(JsonNode arguments, String field) {
        if (arguments == null) return "";
        JsonNode value = arguments.get(field);
        if (value == null || !value.isTextual()) return "";
        return truncate(value.textValue().replace('\r', ' ').replace('\n', ' ').strip());
    }

    private static String safeIdentifier(String value) {
        if (value == null) return "unknown";
        String safe = value.replaceAll("[^a-zA-Z0-9._:/\\\\-]", "");
        return truncate(safe.isBlank() ? "unknown" : safe);
    }

    private static String fallback(String value, String fallback) {
        return value.isBlank() ? fallback : value;
    }

    private static String truncate(String value) {
        return value.length() <= MAX_VALUE ? value : value.substring(0, MAX_VALUE - 3) + "...";
    }
}
