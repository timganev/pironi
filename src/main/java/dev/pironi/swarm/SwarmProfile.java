package dev.pironi.swarm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * The limit learned for each server and model, so the next run starts where the last one ended
 * instead of searching again: {@code {"http://127.0.0.1:8000/v1 qwen36": 8, ...}}. A missing or
 * unreadable file means nothing was learned yet; it is never a reason to fail.
 */
public final class SwarmProfile {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path path;

    public SwarmProfile(Path path) {
        this.path = path;
    }

    public static String key(java.net.URI baseUri, String model) {
        return baseUri.toString().replaceAll("/+$", "") + " " + (model == null ? "" : model);
    }

    /** The limit learned last time, or 0 when there is none. */
    public synchronized int limit(String key) {
        return read().path(key).asInt(0);
    }

    public synchronized void remember(String key, int limit) {
        if (path == null || limit < 1) return;
        ObjectNode all = read();
        if (all.path(key).asInt(0) == limit) return;
        all.put(key, limit);
        try {
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            JSON.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), all);
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ignored) {
            // the next run searches again
        }
    }

    private ObjectNode read() {
        if (path == null || !Files.isRegularFile(path)) return JSON.createObjectNode();
        try {
            return JSON.readTree(path.toFile()) instanceof ObjectNode o ? o : JSON.createObjectNode();
        } catch (IOException e) {
            return JSON.createObjectNode();
        }
    }
}
