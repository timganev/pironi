package dev.pironi.session;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Directories the user chose to keep granted across sessions.
 *
 * <p>A session grant is the right default, but a team lead pointing at the same export folder
 * every morning should not have to say so every morning. Kept as a plain list next to the other
 * Pironi state so it can be inspected and edited by hand.
 *
 * <p>Only ever written by an explicit user command. Nothing here is inferred from usage, because
 * a directory that silently becomes readable forever is exactly the kind of permission creep the
 * session default is protecting against.
 */
public final class RememberedRoots {
    private static final int MAX_ROOTS = 50;
    private final Path file;

    public RememberedRoots(Path pironiHome) {
        this.file = pironiHome.resolve("remembered-roots.txt");
    }

    public List<Path> list() throws IOException {
        if (!Files.exists(file)) return List.of();
        List<Path> roots = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            roots.add(Path.of(trimmed));
        }
        return List.copyOf(roots);
    }

    /** @return true when the directory was added; false when it was already remembered or the list is full */
    public boolean remember(Path directory) throws IOException {
        Path normalized = directory.toAbsolutePath().normalize();
        Set<Path> roots = new LinkedHashSet<>(list());
        if (roots.size() >= MAX_ROOTS || !roots.add(normalized)) return false;
        write(roots);
        return true;
    }

    /** @return true when the directory was remembered and is now removed */
    public boolean forget(Path directory) throws IOException {
        Path normalized = directory.toAbsolutePath().normalize();
        Set<Path> roots = new LinkedHashSet<>(list());
        if (!roots.remove(normalized)) return false;
        write(roots);
        return true;
    }

    private void write(Set<Path> roots) throws IOException {
        StringBuilder out = new StringBuilder(
                "# Directories granted across sessions by /access remember-dir.\n"
                        + "# Remove a line here or use /access forget-dir PATH.\n");
        for (Path root : roots) out.append(root).append('\n');
        Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
    }
}
