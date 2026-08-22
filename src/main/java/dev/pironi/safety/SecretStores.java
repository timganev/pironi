package dev.pironi.safety;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The places credentials live, named rather than guessed at. A guard against wandering into a key
 * by accident, not a sandbox.
 */
public final class SecretStores {
    private SecretStores() {
    }

    public static boolean isProtected(Path path) {
        return protectedBy(path) != null;
    }

    /** @return the store containing this path, or null when it is not inside one */
    public static Path protectedBy(Path path) {
        return protectedBy(path, System.getProperty("os.name", ""),
                System.getProperty("user.home", ""), System.getenv());
    }

    static Path protectedBy(Path path, String osName, String userHome, Map<String, String> env) {
        if (path == null) return null;
        Path lexical = path.toAbsolutePath().normalize();
        // Normalising the text is not enough to know where a path lands. Windows ships a short
        // 8.3 alias for every long name and a set of junctions kept for XP's sake, so .ssh is also
        // SSH~1, and %APPDATA% is also "Application Data" - all of them real, all of them
        // arriving here as something that starts with no store at all. What the file system says
        // the path is decides, with the text as the fallback for names not on disk yet.
        Path resolved = canonical(lexical);
        for (Path store : stores(osName, userHome, env)) {
            if (within(lexical, store)) return store;
            // Both sides have to be resolved or neither: on macOS /var is itself a link to
            // /private/var, so a resolved path compared against an unresolved store matches
            // nothing. The store is reported under the name it is known by, not its real one.
            Path realStore = canonical(store);
            if (resolved != null && realStore != null && within(resolved, realStore)) return store;
        }
        return null;
    }

    private static boolean within(Path candidate, Path store) {
        return candidate.equals(store) || candidate.startsWith(store);
    }

    /**
     * The path the file system agrees on, or null when nothing along it exists. Walks up to the
     * nearest real ancestor, so a key that has not been written yet still resolves through the
     * aliases above it.
     */
    private static Path canonical(Path path) {
        if (path == null) return null;
        try {
            return path.toRealPath();
        } catch (java.io.IOException | RuntimeException e) {
            Path parent = canonical(path.getParent());
            Path name = path.getFileName();
            return parent == null || name == null ? null : parent.resolve(name);
        }
    }

    /** The stores for a platform, resolved from this machine's home directory and environment. */
    public static List<Path> stores(String osName) {
        return stores(osName, System.getProperty("user.home", ""), System.getenv());
    }

    static List<Path> stores(String osName, String userHome, Map<String, String> env) {
        boolean windows = osName.toLowerCase(Locale.ROOT).contains("win");
        boolean mac = osName.toLowerCase(Locale.ROOT).contains("mac");
        List<Path> stores = new ArrayList<>();
        if (userHome != null && !userHome.isBlank()) {
            Path home = Path.of(userHome).toAbsolutePath().normalize();
            for (String relative : List.of(
                    ".ssh", ".gnupg", ".aws", ".kube", ".netrc", ".password-store",
                    ".docker/config.json", ".config/gh", ".npmrc", ".pypirc"
            )) {
                stores.add(home.resolve(relative.replace("/", java.io.File.separator)));
            }
            if (mac) stores.add(home.resolve("Library").resolve("Keychains"));
            if (!mac && !windows) {
                stores.add(home.resolve(".local").resolve("share").resolve("keyrings"));
            }
        }
        if (windows) {
            addEnvPath(stores, env, "APPDATA", "Microsoft", "Crypto");
            addEnvPath(stores, env, "APPDATA", "Microsoft", "Protect");
            addEnvPath(stores, env, "APPDATA", "Microsoft", "SystemCertificates");
            addEnvPath(stores, env, "LOCALAPPDATA", "Microsoft", "Credentials");
        } else {
            stores.add(Path.of("/etc/shadow"));
            stores.add(Path.of("/etc/sudoers"));
        }
        return List.copyOf(stores);
    }

    private static void addEnvPath(List<Path> stores, Map<String, String> env,
            String variable, String... segments) {
        String base = env == null ? null : env.get(variable);
        if (base == null || base.isBlank()) return;
        Path path = Path.of(base).toAbsolutePath().normalize();
        for (String segment : segments) path = path.resolve(segment);
        stores.add(path);
    }
}
