package dev.pironi.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.pironi.model.ChatMessage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Filesystem-based session store. One JSONL file per session + meta + checkpoint.
 */
public final class SessionStore {
    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HHmm").withZone(ZoneId.systemDefault());

    private final Path sessionsDir;
    private final ObjectMapper mapper;
    private Path currentPath;
    private SessionMeta currentMeta;

    public SessionStore(Path pironiHome, ObjectMapper mapper) throws IOException {
        this.sessionsDir = pironiHome.resolve("sessions");
        Files.createDirectories(sessionsDir);
        this.mapper = mapper;
    }

    // ── create ─────────────────────────────────────────────────────────

    public SessionMeta startSession(String model, Path workspace, int contextLimit, int maxTurns) {
        String rawSlug = workspace.getFileName() != null
                ? workspace.getFileName().toString() : "session";
        String slug = rawSlug.replaceAll("[^a-zA-Z0-9._-]", "-");
        if (slug.isBlank()) slug = "session";
        String ts = FMT.format(Instant.now());
        String id = ts + "-" + slug + "-" + UUID.randomUUID().toString().substring(0, 8);
        currentPath = sessionsDir.resolve(id + ".jsonl");
        currentMeta = new SessionMeta(id, model, workspace.toString(), contextLimit, maxTurns,
                Instant.now().toString(), 0, 0, "active");
        return currentMeta;
    }

    // ── write ──────────────────────────────────────────────────────────

    public synchronized void appendTurn(ChatMessage message, long promptTokens, long outputTokens) {
        if (currentPath == null) return;
        ObjectNode node = mapper.createObjectNode()
                .put("ts", Instant.now().toString())
                .put("role", message.role())
                .put("content", message.content())
                .put("promptTokens", promptTokens)
                .put("outputTokens", outputTokens);
        try {
            Files.writeString(currentPath, SecretRedactor.redact(mapper.writeValueAsString(node)) + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            currentMeta = currentMeta.withTokens(
                    currentMeta.totalPromptTokens() + promptTokens,
                    currentMeta.totalOutputTokens() + outputTokens
            );
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized void appendToolResult(String tool, JsonNode args, String output) {
        if (currentPath == null) return;
        ObjectNode node = mapper.createObjectNode()
                .put("ts", Instant.now().toString())
                .put("role", "tool")
                .put("content", output)
                .put("tool", tool);
        if (args != null) node.set("args", args);
        try {
            Files.writeString(currentPath, SecretRedactor.redact(mapper.writeValueAsString(node)) + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ── meta ───────────────────────────────────────────────────────────

    public void saveMeta() {
        if (currentMeta == null) return;
        writeJson(sessionsDir.resolve(currentMeta.id() + ".meta.json"), mapper.valueToTree(currentMeta));
        updateIndex(currentMeta);
    }

    public SessionMeta currentMeta() { return currentMeta; }

    public synchronized void updateStatus(String status) {
        if (currentMeta == null) return;
        currentMeta = currentMeta.withStatus(status);
        saveMeta();
    }

    // ── checkpoint ─────────────────────────────────────────────────────

    public void saveCheckpoint(String compressedJson) {
        if (currentMeta == null) return;
        try {
            Files.writeString(sessionsDir.resolve(currentMeta.id() + ".ckpt.json"),
                    SecretRedactor.redact(compressedJson),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Optional<String> loadCheckpoint(String sessionId) {
        if (!validId(sessionId)) return Optional.empty();
        Path p = sessionsDir.resolve(sessionId + ".ckpt.json");
        if (!Files.exists(p)) return Optional.empty();
        try {
            return Optional.of(Files.readString(p, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    // ── list / load / delete ───────────────────────────────────────────

    public List<SessionMeta> listSessions() throws IOException {
        var metas = new ArrayList<SessionMeta>();
        try (Stream<Path> files = Files.list(sessionsDir)) {
            files.filter(p -> p.toString().endsWith(".meta.json"))
                    .forEach(p -> loadMeta(p).ifPresent(metas::add));
        }
        metas.sort(Comparator.comparing(SessionMeta::created).reversed());
        return metas;
    }

    public Optional<SessionMeta> loadMeta(Path metaPath) {
        try {
            JsonNode node = mapper.readTree(Files.readString(metaPath, StandardCharsets.UTF_8));
            return Optional.of(new SessionMeta(
                    node.path("id").asText(),
                    node.path("model").asText(),
                    node.path("workspace").asText(),
                    node.path("contextLimit").asInt(),
                    node.path("maxTurns").asInt(),
                    node.path("created").asText(),
                    node.path("totalPromptTokens").asLong(),
                    node.path("totalOutputTokens").asLong(),
                    node.path("status").asText("active")
            ));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public List<String> readSessionMessages(String sessionId) throws IOException {
        if (!validId(sessionId)) return List.of();
        Path jsonl = sessionsDir.resolve(sessionId + ".jsonl");
        if (!Files.exists(jsonl)) return List.of();
        var messages = new ArrayList<String>();
        for (String line : Files.readAllLines(jsonl, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) messages.add(line);
        }
        return messages;
    }

    public boolean deleteSession(String sessionId) {
        if (!validId(sessionId)) return false;
        try {
            boolean deleted = Files.deleteIfExists(sessionsDir.resolve(sessionId + ".jsonl"));
            deleted |= Files.deleteIfExists(sessionsDir.resolve(sessionId + ".meta.json"));
            deleted |= Files.deleteIfExists(sessionsDir.resolve(sessionId + ".ckpt.json"));
            rebuildIndex();
            return deleted;
        } catch (IOException e) {
            return false;
        }
    }

    public Optional<String> latestSessionId() {
        try {
            return listSessions().stream().findFirst().map(SessionMeta::id);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    // ── search ─────────────────────────────────────────────────────────

    public List<String> searchSessions(String query) throws IOException {
        var results = new ArrayList<String>();
        try (Stream<Path> files = Files.list(sessionsDir)) {
            files.filter(p -> p.toString().endsWith(".jsonl"))
                    .forEach(p -> {
                        try {
                            String content = Files.readString(p, StandardCharsets.UTF_8);
                            if (content.toLowerCase().contains(query.toLowerCase())) {
                                results.add(p.getFileName().toString().replace(".jsonl", ""));
                            }
                        } catch (IOException ignored) { }
                    });
        }
        return results;
    }

    // ── index ──────────────────────────────────────────────────────────

    private void updateIndex(SessionMeta meta) {
        Path index = sessionsDir.resolve("INDEX.md");
        try {
            var lines = new ArrayList<String>();
            if (Files.exists(index)) {
                lines.addAll(Files.readAllLines(index, StandardCharsets.UTF_8));
            }
            String entry = String.format("| %s | %s | %s | %s | %s |",
                    meta.created().substring(0, 16), meta.status(), meta.model(),
                    meta.workspace(), meta.totalPromptTokens() + meta.totalOutputTokens());
            boolean found = false;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).contains(meta.id())) {
                    lines.set(i, entry + " " + meta.id());
                    found = true;
                    break;
                }
            }
            if (!found) lines.add(entry + " " + meta.id());
            Files.write(index, lines, StandardCharsets.UTF_8);
        } catch (IOException ignored) { }
    }

    private void rebuildIndex() throws IOException {
        Path index = sessionsDir.resolve("INDEX.md");
        Files.deleteIfExists(index);
        for (SessionMeta m : listSessions()) updateIndex(m);
    }

    // ── helpers ────────────────────────────────────────────────────────

    private void writeJson(Path path, JsonNode node) {
        try {
            Files.writeString(path, mapper.writeValueAsString(node), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean validId(String id) {
        return id != null && !id.isBlank() && id.matches("[a-zA-Z0-9._-]+");
    }

    public record SessionMeta(
            String id, String model, String workspace,
            int contextLimit, int maxTurns,
            String created, long totalPromptTokens, long totalOutputTokens,
            String status
    ) {
        public SessionMeta withTokens(long prompt, long output) {
            return new SessionMeta(id, model, workspace, contextLimit, maxTurns,
                    created, prompt, output, status);
        }

        public SessionMeta withStatus(String newStatus) {
            return new SessionMeta(id, model, workspace, contextLimit, maxTurns,
                    created, totalPromptTokens, totalOutputTokens, newStatus);
        }

        public long totalTokens() { return totalPromptTokens + totalOutputTokens; }
    }
}
