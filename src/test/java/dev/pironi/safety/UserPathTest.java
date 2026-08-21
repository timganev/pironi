package dev.pironi.safety;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserPathTest {
    @Test
    void aLeadingTildeIsHomeAndNotADirectoryNamedTilde() {
        // The shell expanded it and the file tools did not, so the same string worked in
        // run_command and resolved to <workspace>/~/... one turn later.
        Path home = Path.of(System.getProperty("user.home"));

        assertEquals(home, UserPath.of("~"));
        assertEquals(home.resolve(".pironi/skills"), UserPath.of("~/.pironi/skills"));
        assertTrue(UserPath.of("~/x").isAbsolute());
    }

    @Test
    void everythingElseIsLeftAlone() {
        assertEquals(Path.of("notes.md"), UserPath.of("notes.md"));
        assertEquals(Path.of("dir/notes.md"), UserPath.of("dir/notes.md"));
        // A tilde inside the name is part of the name - Windows short names look like this.
        assertEquals(Path.of("RUNNER~1/x"), UserPath.of("RUNNER~1/x"));
        assertEquals(Path.of(""), UserPath.of(""));
    }
}
