package dev.pironi.safety;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Permissions the user widens or narrows while a session runs. Startup flags decide where it
 * begins; this holds what has been granted since, so a blocked path does not cost the
 * conversation. Only the interactive shell mutates it - reachable from a tool, an injected
 * instruction in someone else's document becomes a privilege escalation.
 */
public final class AccessGrants {
    private final Set<Path> grantedRoots = ConcurrentHashMap.newKeySet();
    private final Set<String> disabledTools = ConcurrentHashMap.newKeySet();

    /** Roots granted after startup, canonicalised. Never null, possibly empty. */
    public List<Path> grantedRoots() {
        return List.copyOf(grantedRoots);
    }

    /**
     * Grants read access to a directory for the rest of the session.
     *
     * @return the canonical path that was granted
     * @throws IOException if the path is missing or not a directory
     */
    public Path grantRoot(Path candidate) throws IOException {
        Path real = candidate.toAbsolutePath().normalize();
        if (!Files.isDirectory(real)) {
            throw new IOException("Not an existing directory: " + real);
        }
        real = real.toRealPath();
        grantedRoots.add(real);
        return real;
    }

    /** @return true when the root was granted and is now removed */
    public boolean revokeRoot(Path candidate) {
        Path normalized = candidate.toAbsolutePath().normalize();
        return grantedRoots.remove(normalized)
                || grantedRoots.removeIf(root -> root.equals(normalized));
    }

    public boolean isToolDisabled(String name) {
        return disabledTools.contains(name);
    }

    public Set<String> disabledTools() {
        return Set.copyOf(disabledTools);
    }

    public void disableTool(String name) {
        disabledTools.add(name);
    }

    /** @return true when the tool was disabled and is now enabled */
    public boolean enableTool(String name) {
        return disabledTools.remove(name);
    }
}
