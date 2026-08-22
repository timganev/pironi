package dev.pironi.safety;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lists are asserted by name and the matching is asserted against the host's own
 * filesystem. java.nio.Path cannot represent a foreign layout - "/home/me" on Windows and
 * "C:\\Users\\me" on Unix both arrive as something else - so a test that spells another
 * platform's paths ends up measuring the runner rather than this code.
 */
class SecretStoresTest {
    @TempDir Path home;

    private static final Map<String, String> WINDOWS_ENV = Map.of(
            "APPDATA", "C:\\Users\\me\\AppData\\Roaming",
            "LOCALAPPDATA", "C:\\Users\\me\\AppData\\Local"
    );

    @Test
    void guardsTheKeysAndNotTheOrdinaryHiddenThings() {
        String os = System.getProperty("os.name", "");

        assertNotNull(protectedBy(home.resolve(".ssh").resolve("id_rsa"), os));
        assertNotNull(protectedBy(home.resolve(".aws").resolve("credentials"), os));
        assertNotNull(protectedBy(home.resolve(".gnupg"), os));

        // Hiddenness is the wrong test: .pironi and .git are hidden and ordinary, and blocking
        // them would stop the agent reading the identity file it was just taught to find.
        assertNull(protectedBy(home.resolve(".pironi").resolve("SOUL.md"), os));
        assertNull(protectedBy(home.resolve("app").resolve(".git").resolve("config"), os));
        assertNull(protectedBy(home.resolve(".config").resolve("nvim").resolve("init.lua"), os));
    }

    @Test
    void eachPlatformNamesItsOwnStores() {
        assertTrue(names("Linux").stream().anyMatch(n -> n.endsWith("shadow")), names("Linux") + "");
        assertTrue(names("Linux").stream().anyMatch(n -> n.contains("keyrings")), names("Linux") + "");
        assertTrue(names("Mac OS X").stream().anyMatch(n -> n.endsWith("Keychains")),
                names("Mac OS X") + "");
        // The macOS keychain lives under ~/Library, which the Outlook work reads all week; only
        // the keychain itself is off limits.
        assertTrue(names("Mac OS X").stream().noneMatch(n -> n.contains("Group Containers")),
                names("Mac OS X") + "");
    }

    @Test
    void windowsStoresComeFromTheEnvironmentNotFromADotPrefix() {
        // On Windows "hidden" is an attribute and AppData carries it, so a hiddenness rule would
        // forbid the same Outlook data that reads freely on macOS.
        var stores = SecretStores.stores("Windows 11", "C:\\Users\\me", WINDOWS_ENV).stream()
                .map(Path::toString).toList();

        assertTrue(stores.stream().anyMatch(s -> s.endsWith("Protect")), stores.toString());
        assertTrue(stores.stream().anyMatch(s -> s.endsWith("Credentials")), stores.toString());
        assertTrue(stores.stream().anyMatch(s -> s.endsWith("Crypto")), stores.toString());
        assertTrue(stores.stream().anyMatch(s -> s.contains(".ssh")), stores.toString());

        assertTrue(stores.stream().noneMatch(s -> s.contains("Outlook")), stores.toString());
        assertTrue(stores.stream().noneMatch(s -> s.contains("shadow")), stores.toString());
    }

    private Path protectedBy(Path path, String os) {
        return SecretStores.protectedBy(path, os, home.toString(), Map.of());
    }

    private List<String> names(String os) {
        return SecretStores.stores(os, home.toString(), Map.of()).stream()
                .map(Path::toString).toList();
    }

    @Test
    void anAliasThatLandsInAStoreIsStillTheStore() throws Exception {
        java.nio.file.Path store = java.nio.file.Files.createDirectories(home.resolve(".ssh"));
        java.nio.file.Files.writeString(store.resolve("id_rsa"), "key");
        java.nio.file.Path alias = home.resolve("by-another-name");
        try {
            java.nio.file.Files.createSymbolicLink(alias, store);
        } catch (java.io.IOException | UnsupportedOperationException e) {
            org.junit.jupiter.api.Assumptions.abort("this account may not create links: " + e);
        }

        // Windows hands out a second name for everything: .ssh is also SSH~1, and %APPDATA% is
        // also the junction "Application Data". Normalising the text sees neither, so what the
        // file system says the path is has to decide.
        assertNotNull(protectedBy(alias.resolve("id_rsa"), System.getProperty("os.name", "")));
    }

    @Test
    void aKeyNotWrittenYetIsGuardedThroughAnAliasToo() throws Exception {
        java.nio.file.Path store = java.nio.file.Files.createDirectories(home.resolve(".ssh"));
        java.nio.file.Path alias = home.resolve("by-another-name");
        try {
            java.nio.file.Files.createSymbolicLink(alias, store);
        } catch (java.io.IOException | UnsupportedOperationException e) {
            org.junit.jupiter.api.Assumptions.abort("this account may not create links: " + e);
        }

        // The file the agent is about to write does not exist yet, so only the directories above
        // it can be resolved. That is enough to know where it would land.
        assertNotNull(protectedBy(alias.resolve("id_ed25519"), System.getProperty("os.name", "")));
    }

    @Test
    void anAliasThatLandsSomewhereOrdinaryStaysFree() throws Exception {
        java.nio.file.Path ordinary = java.nio.file.Files.createDirectories(home.resolve("notes"));
        java.nio.file.Path alias = home.resolve("by-another-name");
        try {
            java.nio.file.Files.createSymbolicLink(alias, ordinary);
        } catch (java.io.IOException | UnsupportedOperationException e) {
            org.junit.jupiter.api.Assumptions.abort("this account may not create links: " + e);
        }

        assertNull(protectedBy(alias.resolve("todo.md"), System.getProperty("os.name", "")));
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void aWindowsJunctionIntoAStoreIsStillTheStore() throws Exception {
        // The symlink tests above are skipped on an account without the privilege, which is the
        // ordinary case on Windows - and Windows is where the second names come from. A junction
        // needs no privilege, and "Application Data" and "Local Settings" are exactly this.
        java.nio.file.Path store = java.nio.file.Files.createDirectories(home.resolve(".ssh"));
        java.nio.file.Files.writeString(store.resolve("id_rsa"), "key");
        java.nio.file.Path alias = home.resolve("Application Data");
        Process mklink = new ProcessBuilder("cmd", "/c", "mklink", "/J",
                alias.toString(), store.toString()).redirectErrorStream(true).start();
        if (!mklink.waitFor(20, java.util.concurrent.TimeUnit.SECONDS) || mklink.exitValue() != 0) {
            org.junit.jupiter.api.Assumptions.abort("mklink unavailable: "
                    + new String(mklink.getInputStream().readAllBytes()));
        }

        assertNotNull(protectedBy(alias.resolve("id_rsa"), "Windows 11"));
        assertNotNull(protectedBy(alias.resolve("not-written-yet"), "Windows 11"));
    }
}
