package dev.pironi.safety;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The places credentials live, named rather than guessed at.
 *
 * <p>Hiddenness is the wrong test. On Unix "hidden" means a leading dot, which covers
 * {@code ~/.ssh} but also {@code ~/.config}, {@code .git} and Pironi's own {@code ~/.pironi} -
 * blocking those blocks ordinary work, including the agent reading the identity file it was
 * just taught to find. On Windows "hidden" is a file attribute, and {@code AppData} carries it,
 * so the same rule would forbid the Outlook data that the same task reads freely on macOS. And
 * it is wrong in both directions anyway: {@code ~/.config} is hidden and dull, a password file
 * in Documents is visible and not.
 *
 * <p>So this is a list of specific stores per platform. It is not a sandbox - a determined
 * command can still assemble a path - it is a guard against wandering into a key by accident.
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
