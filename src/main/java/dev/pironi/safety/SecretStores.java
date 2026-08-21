package dev.pironi.safety;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The places credentials live, named rather than guessed at. Hiddenness is the wrong test both
 * ways: on Unix it covers {@code ~/.config}, {@code .git} and Pironi's own {@code ~/.pironi}; on
 * Windows it covers {@code AppData}, forbidding the Outlook data macOS reads freely - and a
 * password file in Documents stays visible either way. A guard, not a sandbox.
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
        Path candidate = path.toAbsolutePath().normalize();
        for (Path store : stores(osName, userHome, env)) {
            if (candidate.equals(store) || candidate.startsWith(store)) return store;
        }
        return null;
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
