package dev.pironi.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildVersionTest {
    @Test void reportsSomethingUsableEvenFromAPlainClasspath() {
        String version = BuildVersion.current();
        assertNotNull(version);
        assertFalse(version.isBlank(), "a build must always identify itself");
        // Running from target/classes there is no packaged version marker, so "dev" is correct;
        // a portable archive carries version.txt beside the jar and reports the release.
        assertTrue(version.equals("dev") || version.startsWith("v"),
                "unexpected version string: " + version);
    }

    @Test void repeatedCallsAgree() {
        assertTrue(BuildVersion.current().equals(BuildVersion.current()),
                "the version is cached and must not change between calls");
    }
}
