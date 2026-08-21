package dev.pironi.safety;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecretStoresTest {
    private static final Map<String, String> WINDOWS_ENV = Map.of(
            "APPDATA", "C:\\Users\\me\\AppData\\Roaming",
            "LOCALAPPDATA", "C:\\Users\\me\\AppData\\Local"
    );

    @Test
    void guardsTheKeysAndNotTheOrdinaryHiddenThings() {
        // Hiddenness is the wrong test: .pironi and .git are hidden and ordinary, and blocking
        // them would stop the agent reading the identity file it was just taught to find.
        assertNotNull(protectedBy("/home/me/.ssh/id_rsa", "Linux", "/home/me"));
        assertNotNull(protectedBy("/home/me/.aws/credentials", "Linux", "/home/me"));
        assertNotNull(protectedBy("/etc/shadow", "Linux", "/home/me"));

        assertNull(protectedBy("/home/me/.pironi/SOUL.md", "Linux", "/home/me"));
        assertNull(protectedBy("/home/me/projects/app/.git/config", "Linux", "/home/me"));
        assertNull(protectedBy("/home/me/.config/nvim/init.lua", "Linux", "/home/me"));
    }

    @Test
    void theMacKeychainIsAStoreAndTheLibraryIsNot() {
        // The Outlook work reads ~/Library all week; only the keychain inside it is off limits.
        assertNotNull(protectedBy(
                "/Users/me/Library/Keychains/login.keychain-db", "Mac OS X", "/Users/me"));
        assertNull(protectedBy(
                "/Users/me/Library/Group Containers/UBF8T346G9.Office/x", "Mac OS X", "/Users/me"));
    }

    @Test
    void windowsStoresComeFromTheEnvironmentNotFromADotPrefix() {
        // On Windows "hidden" is an attribute and AppData carries it, so a hiddenness rule would
        // forbid the same Outlook data that reads freely on macOS. Only the key stores are named.
        //
        // The list is asserted rather than the matching: java.nio.Path cannot represent a
        // Windows path on a Unix filesystem - "C:\\Users\\me" arrives as a single name - so a
        // startsWith comparison here would test the host's path rules, not this code.
        var stores = SecretStores.stores("Windows 11", "C:\\Users\\me", WINDOWS_ENV).stream()
                .map(Path::toString).toList();

        assertTrue(stores.stream().anyMatch(s -> s.contains("Microsoft") && s.endsWith("Protect")),
                stores.toString());
        assertTrue(stores.stream().anyMatch(s -> s.endsWith("Credentials")), stores.toString());
        assertTrue(stores.stream().anyMatch(s -> s.endsWith("Crypto")), stores.toString());
        assertTrue(stores.stream().anyMatch(s -> s.contains(".ssh")), stores.toString());

        // Outlook's own data is not a credential store and must stay readable.
        assertTrue(stores.stream().noneMatch(s -> s.contains("Outlook")), stores.toString());
        // Nor is /etc/shadow, which does not exist there.
        assertTrue(stores.stream().noneMatch(s -> s.contains("shadow")), stores.toString());
    }

    private static Path protectedBy(String path, String os, String home) {
        return SecretStores.protectedBy(Path.of(path), os, home, Map.of());
    }
}
