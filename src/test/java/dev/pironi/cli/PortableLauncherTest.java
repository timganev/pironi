package dev.pironi.cli;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two launchers are one product, and a release is meant to behave like every other way of
 * running Pironi. The Unix one once carried none of the Windows defaults, so a bundle unzipped on
 * Linux read a different home and never saw the skills it was carrying.
 */
class PortableLauncherTest {
    private static String read(String... parts) throws Exception {
        return Files.readString(Path.of(parts[0],
                java.util.Arrays.copyOfRange(parts, 1, parts.length)));
    }

    @Test
    void bothLaunchersTellPironiWhereTheBundleIs() throws Exception {
        // Nothing else can know: the jar sees a working directory, not the folder it shipped in.
        assertTrue(read("dist", "windows", "pironi.bat").contains("PIRONI_BUNDLE_DIR="));
        assertTrue(read("dist", "unix", "pironi").contains("PIRONI_BUNDLE_DIR="));
        assertTrue(read("dist", "unix", "pironi").contains("export PIRONI_BUNDLE_DIR"));
    }

    /**
     * The packagers copy README.md into the bundle and nothing else from the repository, so a
     * relative link in it points at a directory that is not there. Moving three sections into
     * docs/ put three dead links in front of everyone reading the archive, which is the copy most
     * people read.
     */
    @Test
    void theReadmeLinksNothingTheBundleDoesNotCarry() throws Exception {
        String readme = Files.readString(Path.of("README.md"));
        java.util.regex.Matcher relative =
                java.util.regex.Pattern.compile("]\\((?!https?://)([^)]+)\\)").matcher(readme);
        List<String> unreachable = new java.util.ArrayList<>();
        while (relative.find()) {
            String target = relative.group(1);
            if (target.startsWith("#")) continue;
            String top = target.split("/")[0];
            // What the packagers put beside the README, and nothing else.
            if (!List.of("skills", "dist", "scripts", "SOUL.example.md", "USER.example.md")
                    .contains(top)) {
                unreachable.add(target);
            }
        }
        assertTrue(unreachable.isEmpty(),
                "README links these from inside the bundle, where they do not exist: "
                        + unreachable + " - use an absolute URL");
    }

    @Test
    void neitherLauncherMovesTheHome() throws Exception {
        // A release keeps its skills and memory in ~/.pironi like any other run, so upgrading is
        // unzipping a new folder. Pinning the home inside the bundle is what --portable is for,
        // and that belongs to Pironi rather than to a shell script.
        for (List<String> file : List.of(List.of("dist", "windows", "pironi.bat"),
                List.of("dist", "unix", "pironi"))) {
            String launcher = read(file.toArray(new String[0]));
            assertTrue(!launcher.contains("PIRONI_DEFAULT_HOME"),
                    file + " must not pin the home; --portable does that");
        }
    }

    @Test
    void theWorkspaceIsOnlyRedirectedWhenTheBundleIsTheWorkingDirectory() throws Exception {
        // Double-clicked, the working directory is the program's own folder and an agent would
        // write into it. Run from a shell, the directory the person stood in is the one they meant.
        String windows = read("dist", "windows", "pironi.bat");
        assertTrue(windows.contains("%CD%"), "the check has to compare the working directory");
        assertTrue(windows.contains("PIRONI_DEFAULT_WORKSPACE=%USERPROFILE%"));
        String unix = read("dist", "unix", "pironi");
        assertTrue(unix.contains("$PWD"), "the check has to compare the working directory");
        assertTrue(unix.contains("PIRONI_DEFAULT_WORKSPACE:-$HOME"));
    }

    @Test
    void neitherLauncherStartsWithoutItsRuntime() throws Exception {
        for (List<String> file : List.of(List.of("dist", "windows", "pironi.bat"),
                List.of("dist", "unix", "pironi"))) {
            String launcher = read(file.toArray(new String[0]));
            assertTrue(launcher.contains("runtime"), file + " must name the bundled runtime");
            assertTrue(launcher.contains("pironi.jar"), file + " must run the jar");
        }
    }

    @Test
    void portableKeepsEverythingInsideTheBundle() {
        Map<String, String> environment = Map.of("PIRONI_BUNDLE_DIR", "/opt/pironi-bundle");

        CliOptions portable = CliOptions.parse(
                new String[]{"--provider", "ollama", "--model", "m", "--portable"}, environment);
        CliOptions ordinary = CliOptions.parse(
                new String[]{"--provider", "ollama", "--model", "m"}, environment);

        assertEquals(Path.of("/opt/pironi-bundle", ".pironi").toAbsolutePath().normalize(),
                portable.pironiHome());
        assertEquals(Path.of(System.getProperty("user.home"), ".pironi")
                        .toAbsolutePath().normalize(),
                ordinary.pironiHome(), "without the flag a release shares the ordinary home");
    }

    @Test
    void portableWithoutABundleFallsBackRatherThanGuessing() {
        // Run from a checkout there is no bundle to be portable inside, and inventing one would
        // put the person's sessions somewhere they never chose.
        CliOptions options = CliOptions.parse(
                new String[]{"--provider", "ollama", "--model", "m", "--portable"}, Map.of());

        assertEquals(Path.of(System.getProperty("user.home"), ".pironi")
                .toAbsolutePath().normalize(), options.pironiHome());
    }

    @Test
    void anExplicitHomeStillWinsOverPortable() {
        CliOptions options = CliOptions.parse(
                new String[]{"--provider", "ollama", "--model", "m", "--portable",
                        "--pironi-home", "/tmp/chosen"},
                Map.of("PIRONI_BUNDLE_DIR", "/opt/pironi-bundle"));

        assertEquals(Path.of("/tmp/chosen").toAbsolutePath().normalize(), options.pironiHome());
    }
}
