package dev.pironi.safety;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class Workspace {
    private final Path root;

    public Workspace(Path root) throws IOException {
        Path normalized = root.toAbsolutePath().normalize();
        if (!Files.exists(normalized)) {
            Files.createDirectories(normalized);
        }
        this.root = normalized.toRealPath();
        if (!Files.isDirectory(this.root)) {
            throw new IllegalArgumentException("workspace must be a directory: " + root);
        }
    }

    public Path root() {
        return root;
    }

    public Path resolveExisting(String relativePath) throws IOException {
        Path candidate = resolveLexically(relativePath);
        Path real = candidate.toRealPath();
        ensureInside(real);
        return real;
    }

    public Path resolveForWrite(String relativePath) throws IOException {
        Path candidate = resolveLexically(relativePath);
        Path parent = candidate.getParent();
        if (parent == null) {
            throw new IOException("Path has no parent: " + relativePath);
        }
        Path realParent = parent.toRealPath();
        ensureInside(realParent);
        return realParent.resolve(candidate.getFileName());
    }

    public Path resolveForWriteCreatingParents(String relativePath) throws IOException {
        Path candidate = validateForWriteCreatingParents(relativePath);
        Path parent = candidate.getParent();
        Files.createDirectories(parent);
        ensureInside(parent.toRealPath());
        return parent.resolve(candidate.getFileName());
    }

    public Path validateForWriteCreatingParents(String relativePath) throws IOException {
        Path candidate = resolveLexically(relativePath);
        Path parent = candidate.getParent();
        if (parent == null) throw new IOException("Path has no parent: " + relativePath);
        Path existing = parent;
        while (existing != null && !Files.exists(existing)) existing = existing.getParent();
        if (existing == null) throw new IOException("No existing parent for: " + relativePath);
        ensureInside(existing.toRealPath());
        return candidate;
    }

    private Path resolveLexically(String relativePath) throws IOException {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IOException("Path must not be blank");
        }
        Path supplied = Path.of(relativePath);
        if (supplied.isAbsolute()) {
            throw new IOException("Absolute paths are not allowed: " + relativePath);
        }
        Path candidate = root.resolve(supplied).normalize();
        ensureInside(candidate);
        return candidate;
    }

    private void ensureInside(Path candidate) throws IOException {
        if (!candidate.startsWith(root)) {
            throw new IOException("Path escapes workspace: " + candidate);
        }
    }
}
