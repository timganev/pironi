package dev.pironi.tool;

import java.util.List;
import java.util.Locale;

/** Builds shell invocations without assuming that every host provides /bin/bash. */
public final class PlatformShell {
    private PlatformShell() {}

    public static List<String> command(String script) {
        return command(script, System.getProperty("os.name", ""));
    }

    static List<String> command(String script, String osName) {
        if (osName.toLowerCase(Locale.ROOT).contains("win")) {
            return List.of("cmd.exe", "/d", "/s", "/c", script);
        }
        // Deliberately without pipefail: "producer | head" is how a large output is sampled, and
        // under pipefail it exits 141. Inside f=$(find . | head -1) && ... that status kills the
        // whole line, leaving an empty failure the agent cannot explain. Cost one run four turns.
        return List.of("/bin/bash", "-c", script);
    }

    public static String name() {
        return name(System.getProperty("os.name", ""));
    }

    static String name(String osName) {
        return osName.toLowerCase(Locale.ROOT).contains("win")
                ? "cmd.exe" : "/bin/bash";
    }
}
