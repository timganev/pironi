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
        String slug = workspace.getFileName() != null ? workspace.getFileName().toString() : "session";
        String ts = FMT.format(Instant.now());
        String id = ts + "-" + slug;
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
            Files.writeString(currentPath, mapper.writeValueAsString(node) + "\n",
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
            Files.writeString(currentPath, mapper.writeValueAsString(node) + "\n",
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

    // ── checkpoint ─────────────────────────────────────────────────────

    public void saveCheckpoint(String compressedJson) {
        if (currentMeta == null) return;
        try {
            Files.writeString(sessionsDir.resolve(currentMeta.id() + ".ckpt.json"), compressedJson,
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Optional<String> loadCheckpoint(String sessionId) {
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
        Path jsonl = sessionsDir.resolve(sessionId + ".jsonl");
        if (!Files.exists(jsonl)) return List.of();
        var messages = new ArrayList<String>();
        for (String line : Files.readAllLines(jsonl, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) messages.add(line);
        }
        return messages;
    }

    public boolean deleteSession(String sessionId) {
        try {
            Files.deleteIfExists(sessionsDir.resolve(sessionId + ".jsonl"));
            Files.deleteIfExists(sessionsDir.resolve(sessionId + ".meta.json"));
            Files.deleteIfExists(sessionsDir.resolve(sessionId + ".ckpt.json"));
            rebuildIndex();
            return true;
        } catch (IOException e) {
            return false;
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
