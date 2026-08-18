package dev.pironi.session;

import dev.pironi.agent.AgentLoop;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Carries what a run established into the next run against the same workspace.
 *
 * <p>Without this every run rediscovers the same dead ends: the same unreadable store, the same
 * empty table, the same interface that answers but returns nothing. Those conclusions cost turns
 * to reach and nothing to keep.</p>
 */
public final class FindingsStore {
    private final Path directory;

    public FindingsStore(Path pironiHome) {
        this.directory = pironiHome.resolve("findings");
    }

    public List<String> load(Path workspace) {
        Path file = fileFor(workspace);
        if (!Files.isRegularFile(file)) return List.of();
        try {
            List<String> lines = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.strip();
                if (!trimmed.isEmpty()) lines.add(trimmed);
            }
            return List.copyOf(lines);
        } catch (IOException e) {
            return List.of();
        }
    }

    public void save(Path workspace, List<String> findings) {
        if (findings.isEmpty()) return;
        // Merge rather than replace: a run that trimmed its in-memory list must not persist the
        // loss, or each run quietly erases part of what the previous one paid to learn.
        List<String> merged = new ArrayList<>(load(workspace));
        for (String finding : findings) {
            if (!merged.contains(finding)) merged.add(finding);
        }
        List<String> kept = merged;
        if (merged.size() > AgentLoop.MAX_FINDINGS) {
            // Keep both ends. Dropping the head would discard exactly the inherited entries the
            // loop pins, and persist a loss the pinning exists to prevent.
            int half = AgentLoop.MAX_FINDINGS / 2;
            kept = new ArrayList<>(merged.subList(0, half));
            kept.addAll(merged.subList(merged.size() - (AgentLoop.MAX_FINDINGS - half), merged.size()));
        }
        try {
            Files.createDirectories(directory);
            Files.write(fileFor(workspace), kept, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // losing the carry-over must never fail a run
        }
    }

    private Path fileFor(Path workspace) {
        String path = workspace.toAbsolutePath().normalize().toString();
        String name = path.replaceAll("[^A-Za-z0-9]+", "-").replaceAll("^-|-$", "");
        if (name.length() > 120) name = name.substring(name.length() - 120);
        // Squashing separators and truncating makes different workspaces collide, and one
        // project's established facts would then be handed to another.
        return directory.resolve(name + "-" + Integer.toHexString(path.hashCode()) + ".txt");
    }
}
