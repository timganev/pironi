package dev.pironi.session;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Single-line preferences the user asked to keep, in USER.md. */
public final class UserFacts {
    static final String HEADING = "## Запомнено (/remember)";
    private static final int MAX_FACT_CHARACTERS = 400;
    private static final int MAX_FACTS = 100;

    private final Path userFile;

    public UserFacts(Path pironiHome) {
        this.userFile = pironiHome.resolve("USER.md");
    }

    public List<String> list() throws IOException {
        if (!Files.exists(userFile)) return List.of();
        List<String> facts = new ArrayList<>();
        boolean inSection = false;
        for (String line : Files.readString(userFile, StandardCharsets.UTF_8).lines().toList()) {
            if (line.startsWith("## ")) {
                inSection = line.strip().equals(HEADING);
                continue;
            }
            if (inSection && line.strip().startsWith("- ")) {
                facts.add(line.strip().substring(2).strip());
            }
        }
        return List.copyOf(facts);
    }

    /** @return the stored fact, or empty when it was rejected as blank, oversized or duplicate */
    public String add(String fact) throws IOException {
        String cleaned = fact == null ? "" : fact.strip().replaceAll("\\s+", " ");
        if (cleaned.isEmpty() || cleaned.length() > MAX_FACT_CHARACTERS) return "";
        cleaned = SecretRedactor.redact(cleaned);
        List<String> existing = list();
        if (existing.stream().anyMatch(cleaned::equalsIgnoreCase)) return "";
        if (existing.size() >= MAX_FACTS) return "";

        String content = Files.exists(userFile)
                ? Files.readString(userFile, StandardCharsets.UTF_8) : "";
        if (!content.contains(HEADING)) {
            if (!content.isBlank() && !content.endsWith("\n")) content += "\n";
            content += (content.isBlank() ? "" : "\n") + HEADING + "\n";
        }
        int at = content.indexOf(HEADING) + HEADING.length();
        int nextHeading = content.indexOf("\n## ", at);
        int insertAt = nextHeading < 0 ? content.length() : nextHeading;
        String before = content.substring(0, insertAt).stripTrailing();
        String after = content.substring(insertAt);
        AtomicFile.writeString(userFile, before + "\n- " + cleaned + "\n" + after);
        return cleaned;
    }

    /** @return the removed fact, or empty when the index is out of range */
    public String removeAt(int oneBasedIndex) throws IOException {
        List<String> facts = list();
        if (oneBasedIndex < 1 || oneBasedIndex > facts.size()) return "";
        String target = facts.get(oneBasedIndex - 1);
        List<String> out = new ArrayList<>();
        boolean inSection = false;
        boolean removed = false;
        for (String line : Files.readString(userFile, StandardCharsets.UTF_8).lines().toList()) {
            if (line.startsWith("## ")) inSection = line.strip().equals(HEADING);
            else if (inSection && !removed && line.strip().equals("- " + target)) {
                removed = true;
                continue;
            }
            out.add(line);
        }
        AtomicFile.writeString(userFile, String.join("\n", out) + "\n");
        return target;
    }
}
