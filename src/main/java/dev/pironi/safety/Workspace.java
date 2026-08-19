package dev.pironi.safety;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

/**
 * The one directory every writing tool is allowed to touch.
 *
 * <p>Read access can be widened at runtime through {@link AccessGrants}, writing cannot: a
 * granted directory is readable but not writable. That asymmetry is deliberate, and it used to
 * be invisible - a refusal said only "Absolute paths are not allowed", so a file the agent had
 * just read looked, to the same agent, like a file no tool could ever touch. The refusals below
 * name the workspace, say when the path is merely readable, and say how to move the sandbox.
 */
public final class Workspace {
    private volatile Path root;
    private volatile Supplier<List<Path>> readableRoots = List::of;

    public Workspace(Path root) throws IOException {
        this.root = canonical(root);
    }

    public Path root() {
        return root;
    }

    /**
     * Moves the write sandbox for the rest of the session.
     *
     * <p>Every scoped tool holds this same object and asks for {@link #root()} when it runs, so
     * one switch reaches all of them without rebuilding the tool registry. Like {@link
     * AccessGrants}, only the interactive shell calls this: reachable from a tool, a document
     * the model was asked to read could talk it into writing somewhere else entirely.
     *
     * @return the canonical path now in force
     */
    public Path switchTo(Path candidate) throws IOException {
        Path canonical = canonical(candidate);
        this.root = canonical;
        return canonical;
    }

    /** Lets refusals tell apart "not allowed at all" from "readable, but not writable". */
    public void useReadableRoots(Supplier<List<Path>> roots) {
        if (roots != null) this.readableRoots = roots;
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

    private static Path canonical(Path candidate) throws IOException {
        Path normalized = candidate.toAbsolutePath().normalize();
        if (!Files.exists(normalized)) {
            Files.createDirectories(normalized);
        }
        Path real = normalized.toRealPath();
        if (!Files.isDirectory(real)) {
            throw new IllegalArgumentException("workspace must be a directory: " + candidate);
        }
        return real;
    }

    private Path resolveLexically(String relativePath) throws IOException {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IOException("Path must not be blank");
        }
        Path supplied = Path.of(relativePath);
        if (supplied.isAbsolute()) {
            throw outside("Absolute paths are not allowed: " + relativePath,
                    supplied.normalize());
        }
        Path candidate = root.resolve(supplied).normalize();
        ensureInside(candidate);
        return candidate;
    }

    private void ensureInside(Path candidate) throws IOException {
        if (!candidate.startsWith(root)) {
            throw outside("Path escapes the workspace: " + candidate, candidate);
        }
    }

    /**
     * The suggestion names a directory rather than the file itself, because that is what
     * {@code /workspace} and {@code --workspace} take.
     */
    private IOException outside(String refusal, Path candidate) {
        Path directory = Files.isDirectory(candidate) ? candidate : candidate.getParent();
        String target = directory == null ? candidate.toString() : directory.toString();
        String move = " Move the sandbox with /workspace " + target
                + ", or restart with --workspace " + target + ".";
        return new IOException(refusal + " Writing tools act only inside the workspace " + root
                + "."
                + (isReadable(candidate)
                        ? " That path is readable through an allowed root but not writable."
                        : "")
                + move);
    }

    private boolean isReadable(Path candidate) {
        Path real = resolvedAsFarAsPossible(candidate);
        for (Path allowed : readableRoots.get()) {
            if (candidate.startsWith(allowed) || real.startsWith(resolvedAsFarAsPossible(allowed))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The same directory has two spellings on two of the three platforms: /var and /private/var
     * on macOS, RUNNER~1 and runneradmin on Windows. Comparing what was typed against what was
     * granted then says "outside" about a directory that is plainly inside, and the refusal drops
     * the one sentence that would have explained it. Resolve as far as the filesystem allows -
     * the path itself may not exist yet, which is normal for something about to be written.
     */
    static Path resolvedAsFarAsPossible(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        Path existing = absolute;
        while (existing != null && !Files.exists(existing)) existing = existing.getParent();
        if (existing == null) return absolute;
        try {
            Path real = existing.toRealPath();
            return existing.equals(absolute) ? real : real.resolve(existing.relativize(absolute));
        } catch (IOException e) {
            return absolute;
        }
    }
}
