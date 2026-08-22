package dev.pironi.session;

import dev.pironi.agent.FindingsLedger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Carries what a run established into the next run against the same workspace, so the same dead
 * ends are not rediscovered at the cost of turns each time.
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

    /** Adds what this run established, one entry per distinct text. */
    public void save(Path workspace, List<String> texts, String date, String session) {
        if (texts.isEmpty()) return;
        List<Finding> merged = compacted(load(workspace));
        for (String text : texts) {
            // One fact, one line: a newline or a tab in the model's text would otherwise split the
            // record and the next load would read half a sentence as a whole finding.
            // Clipped here too, so an over-long finding never reaches the file: the ledger's
            // ceiling would otherwise only apply to what this run happened to record.
            String value = FindingsLedger.clip(
                    text.replaceAll("[\\p{Cntrl}]+", " ").strip());
            if (value.isEmpty()) continue;
            int existing = indexOfRestatement(merged, value);
            if (existing >= 0) {
                // The same fact restated: keep whichever wording says more, in the place it holds.
                String held = merged.get(existing).text();
                String fuller = value.length() >= held.length() ? value : held;
                merged.set(existing, new Finding(date, session, fuller));
            } else merged.add(new Finding(date, session, value));
        }
        List<Finding> kept = merged;
        if (merged.size() > FindingsLedger.MAX_FINDINGS) {
            // Keep both ends.
            int half = FindingsLedger.MAX_FINDINGS / 2;
            kept = new ArrayList<>(merged.subList(0, half));
            kept.addAll(merged.subList(merged.size() - (FindingsLedger.MAX_FINDINGS - half), merged.size()));
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

    /**
     * Folds rows that only restate one another. A file written before this rule held 34 rows that
     * were one document at 34 lengths; without this it would keep them until the end of time.
     */
    private static List<Finding> compacted(List<Finding> stored) {
        List<Finding> kept = new ArrayList<>();
        for (Finding finding : stored) {
            int existing = indexOfRestatement(kept, finding.text());
            if (existing < 0) {
                kept.add(finding);
            } else if (finding.text().length() > kept.get(existing).text().length()) {
                kept.set(existing, finding);
            }
        }
        return kept;
    }

    /**
     * Where a fact already stands, matched on wording as well as on restatement. Matching on
     * equality alone let one document arrive as a longer prefix each turn and take a row each time.
     */
    private static int indexOfRestatement(List<Finding> findings, String text) {
        for (int i = 0; i < findings.size(); i++) {
            String held = findings.get(i).text();
            if (held.equals(text) || FindingsLedger.restates(held, text)) return i;
        }
        return -1;
    }

    private Path fileFor(Path workspace) {
        String path = workspace.toAbsolutePath().normalize().toString();
        String name = path.replaceAll("[^A-Za-z0-9]+", "-").replaceAll("^-|-$", "");
        if (name.length() > 120) name = name.substring(name.length() - 120);
        // Squashing separators and truncating makes different workspaces collide, and one project's
        // established facts would then be handed to another.
        return directory.resolve(name + "-" + Integer.toHexString(path.hashCode()) + ".txt");
    }
}
