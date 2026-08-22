package dev.pironi.cli;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two launchers are one product. The Unix one carried none of these defaults, so a release
 * unzipped on Linux read ~/.pironi and never saw the skills the packager had copied into its own
 * .pironi/skills - the same bundle, behaving differently by platform.
 */
class PortableLauncherTest {
    private static final Pattern DEFAULT_NAME = Pattern.compile("PIRONI_DEFAULT_[A-Z_]+");

    @Test
    void bothLaunchersSetTheSameDefaults() throws Exception {
        assertEquals(
                namesIn(read("dist", "windows", "pironi.bat")),
                namesIn(read("dist", "unix", "pironi")),
                "a default set on one platform and not the other is a bug that only shows up there"
        );
    }

    @Test
    void everyDefaultTheLaunchersSetIsOneTheProgramReads() throws Exception {
        Set<String> read = namesIn(read("src", "main", "java", "dev", "pironi", "cli",
                "CliOptions.java"));
        for (String name : namesIn(read("dist", "windows", "pironi.bat"))) {
            assertTrue(read.contains(name), name + " is set by the launcher and read by nothing");
        }
    }

    @Test
    void theHomeIsTheBundleSoASkillShippedWithItIsFound() throws Exception {
        // package-windows.ps1 and package-unix.sh both copy skills into <bundle>/.pironi/skills.
        // That path is only ever read when the launcher points the home at the bundle.
        assertTrue(read("dist", "windows", "pironi.bat")
                .contains("PIRONI_DEFAULT_HOME=%PIRONI_DIR%.pironi"));
        assertTrue(read("dist", "unix", "pironi").contains("PIRONI_DEFAULT_HOME:-$PIRONI_DIR/.pironi"));
    }

    @Test
    void anEnvironmentAlreadySetWins() throws Exception {
        // These are defaults. Overwriting a value the person exported themselves gives them no way
        // to move the home, and no clue that the launcher is what took it back.
        assertTrue(read("dist", "unix", "pironi").contains("${PIRONI_DEFAULT_HOME:-"));
        assertTrue(read("dist", "windows", "pironi.bat").contains("if not defined PIRONI_DEFAULT_HOME"));
    }

    @Test
    void neitherLauncherRefusesToStartWithoutItsRuntime() throws Exception {
        for (List<String> file : List.of(List.of("dist", "windows", "pironi.bat"),
                List.of("dist", "unix", "pironi"))) {
            String launcher = read(file.toArray(new String[0]));
            assertTrue(launcher.contains("runtime"), file + " must name the bundled runtime");
            assertTrue(launcher.contains("pironi.jar"), file + " must run the jar");
        }
    }

    private static String read(String... parts) throws Exception {
        return Files.readString(Path.of(parts[0], java.util.Arrays.copyOfRange(parts, 1, parts.length)));
    }

    private static Set<String> namesIn(String text) {
        Set<String> names = new TreeSet<>();
        Matcher matcher = DEFAULT_NAME.matcher(text);
        while (matcher.find()) names.add(matcher.group());
        return names;
    }
}
