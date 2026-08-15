package dev.pironi.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RememberedRootsTest {
    @TempDir Path home;
    @TempDir Path exports;

    @Test void remembersAndForgetsADirectory() throws Exception {
        RememberedRoots roots = new RememberedRoots(home);
        assertTrue(roots.list().isEmpty());
        assertTrue(roots.remember(exports));
        assertEquals(java.util.List.of(exports.toAbsolutePath().normalize()), roots.list());
        assertTrue(roots.forget(exports));
        assertTrue(roots.list().isEmpty());
    }

    @Test void rememberingTwiceIsNotAnError() throws Exception {
        RememberedRoots roots = new RememberedRoots(home);
        assertTrue(roots.remember(exports));
        assertFalse(roots.remember(exports), "second time changes nothing");
        assertEquals(1, roots.list().size());
    }

    @Test void forgettingSomethingUnknownReportsFalse() throws Exception {
        assertFalse(new RememberedRoots(home).forget(exports));
    }

    @Test void pathsAreStoredNormalisedSoDuplicatesCollapse() throws Exception {
        RememberedRoots roots = new RememberedRoots(home);
        roots.remember(exports);
        assertFalse(roots.remember(exports.resolve("sub").resolve("..")),
                "the same directory reached by a different spelling is not a second entry");
        assertEquals(1, roots.list().size());
    }

    @Test void theFileStaysHumanEditable() throws Exception {
        RememberedRoots roots = new RememberedRoots(home);
        roots.remember(exports);
        String content = Files.readString(home.resolve("remembered-roots.txt"), StandardCharsets.UTF_8);
        assertTrue(content.startsWith("#"), "explain the file to whoever opens it: " + content);
        assertTrue(content.contains(exports.toAbsolutePath().normalize().toString()), content);

        // Comments and blank lines a user adds must not break parsing.
        Files.writeString(home.resolve("remembered-roots.txt"),
                "# my note\n\n" + exports.toAbsolutePath().normalize() + "\n", StandardCharsets.UTF_8);
        assertEquals(1, roots.list().size());
    }
}
