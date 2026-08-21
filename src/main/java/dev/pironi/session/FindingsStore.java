package dev.pironi.session;

import dev.pironi.agent.AgentLoop;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Carries what a run established into the next run against the same workspace, so the same dead
 * ends are not rediscovered at the cost of turns each time.
 *
 * <p>Each line records when the fact was last confirmed and by which session. Undated,
 * "established here" reads as a claim about the present - which is how a note about a file
 * deleted the day before came back as current. The id is for {@code /resume}.
 */
public final class FindingsStore {
    private static final String SEPARATOR = "\t";
    private final Path directory;

    public FindingsStore(Path pironiHome) {
        this.directory = pironiHome.resolve("findings");
    }

    /** One durable fact: when it was last confirmed, by which session, and what it says. */
    public record Finding(String date, String session, String text) {
        public Finding {
            date = date == null ? "" : date;
            session = session == null ? "" : session;
            text = text == null ? "" : text;
        }

        /** What the model sees. Undated lines predate the format and stay as they are. */
        public String forPrompt() {
            return date.isEmpty() ? text : "(" + date + ") " + text;
        }
    }

    public List<Finding> load(Path workspace) {
        Path file = fileFor(workspace);
        if (!Files.isRegularFile(file)) return List.of();
        try {
            List<Finding> findings = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.strip();
                if (trimmed.isEmpty()) continue;
                String[] parts = trimmed.split(SEPARATOR, 3);
                findings.add(parts.length < 3
                        ? new Finding("", "", trimmed)
                        : new Finding(parts[0], parts[1], parts[2]));
            }
            return List.copyOf(findings);
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * Adds what this run established, one entry per distinct text. A repeat refreshes the date
     * instead of adding a line - the fact is fresher, not doubled.
     */
    public void save(Path workspace, List<String> texts, String date, String session) {
        if (texts.isEmpty()) return;
        List<Finding> merged = new ArrayList<>(load(workspace));
        for (String text : texts) {
            // One fact, one line: a newline or a tab in the model's text would otherwise split
            // the record and the next load would read half a sentence as a whole finding.
            String value = text.replaceAll("[\\p{Cntrl}]+", " ").strip();
            if (value.isEmpty()) continue;
            int existing = indexOfText(merged, value);
            if (existing >= 0) merged.set(existing, new Finding(date, session, value));
            else merged.add(new Finding(date, session, value));
        }
        List<Finding> kept = merged;
        if (merged.size() > AgentLoop.MAX_FINDINGS) {
            // Keep both ends. Dropping the head would discard exactly the inherited entries the
            // loop pins, and persist a loss the pinning exists to prevent.
            int half = AgentLoop.MAX_FINDINGS / 2;
            kept = new ArrayList<>(merged.subList(0, half));
            kept.addAll(merged.subList(merged.size() - (AgentLoop.MAX_FINDINGS - half), merged.size()));
        }
        try {
            Files.createDirectories(directory);
            Files.write(fileFor(workspace), kept.stream()
                    .map(finding -> finding.date() + SEPARATOR + finding.session()
                            + SEPARATOR + finding.text())
                    .toList(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // losing the carry-over must never fail a run
        }
    }

    /** @return true when a file existed and is now gone */
    public boolean clear(Path workspace) {
        try {
            return Files.deleteIfExists(fileFor(workspace));
        } catch (IOException e) {
            return false;
        }
    }

    private static int indexOfText(List<Finding> findings, String text) {
        for (int i = 0; i < findings.size(); i++) {
            if (findings.get(i).text().equals(text)) return i;
        }
        return -1;
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
