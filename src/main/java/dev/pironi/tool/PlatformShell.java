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
        // Deliberately without pipefail. It reported the honest failure of "false | tail -1",
        // but "producer | head" is the ordinary way to sample a large output, and under pipefail
        // that pipeline exits 141 (SIGPIPE). Inside a substitution - f=$(find . | head -1) && ... -
        // the non-zero status short-circuits the whole command line, so nothing after it runs and
        // the agent sees an empty failure it cannot explain. That cost one run four turns.
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
