package dev.pironi.safety;

import java.nio.file.Path;

/**
 * Turns what a person or a model typed into a path, expanding a leading {@code ~}.
 *
 * <p>The shell expands it and the file tools did not, so the same string worked in
 * {@code run_command} and, one turn later, resolved to {@code <workspace>/~/...} in
 * {@code read_file} - a directory that has never existed. Three other places already expanded it,
 * which is how the gap stayed invisible.
 */
public final class UserPath {
    private UserPath() {
    }

    public static Path of(String path) {
        String home = System.getProperty("user.home", "");
        if (path == null || path.isBlank()) return Path.of("");
        if (path.equals("~")) return Path.of(home);
        if (path.startsWith("~/") || path.startsWith("~\\")) {
            return Path.of(home, path.substring(2));
        }
        return Path.of(path);
    }
}
