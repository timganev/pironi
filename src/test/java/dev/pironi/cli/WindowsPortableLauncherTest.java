package dev.pironi.cli;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowsPortableLauncherTest {
    @Test
    void providesSafeLaptopDefaultsBeforeUserOverrides() throws Exception {
        String launcher = Files.readString(Path.of("dist", "windows", "pironi.bat"));

        int defaults = launcher.indexOf("--workspace \"%USERPROFILE%\"");
        int arguments = launcher.indexOf("%*", defaults);
        assertTrue(defaults >= 0, "portable launcher should provide a writable workspace");
        assertTrue(launcher.contains("--search-roots \"%USERPROFILE%\""));
        assertTrue(launcher.contains("--pironi-home \"%PIRONI_DIR%.pironi\""));
        assertTrue(launcher.contains("--personal-context allow"));
        assertTrue(launcher.contains("--shell-scope user"));
        assertTrue(arguments > defaults, "user arguments must follow and override defaults");
    }
}
