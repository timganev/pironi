package dev.pironi.cli;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class WindowsPortableLauncherTest {
    @Test
    void providesEnvironmentDefaultsWithoutDuplicatingUserOptions() throws Exception {
        String launcher = Files.readString(Path.of("dist", "windows", "pironi.bat"));

        assertTrue(launcher.contains("PIRONI_DEFAULT_WORKSPACE=%USERPROFILE%"));
        assertTrue(launcher.contains("PIRONI_DEFAULT_SEARCH_ROOTS=%USERPROFILE%"));
        assertTrue(launcher.contains("PIRONI_DEFAULT_HOME=%PIRONI_DIR%.pironi"));
        assertTrue(launcher.contains("PIRONI_DEFAULT_PERSONAL_CONTEXT=allow"));
        assertTrue(launcher.contains("PIRONI_DEFAULT_SHELL_SCOPE=user"));
        assertTrue(launcher.contains("-jar \"%PIRONI_DIR%pironi.jar\" %*"));
        assertFalse(launcher.contains("--workspace \"%USERPROFILE%\""));
    }
}
