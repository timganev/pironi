package dev.pironi.safety;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * Windows reserved device names, which are not filenames however they are spelled.
 *
 * <p>Java writes one through NIO without complaint and reads it back, so the harness stays
 * self-consistent while the result is a directory entry no Win32 path API can open: PowerShell,
 * cmd and Explorer all report it missing. Refusing the name is the only outcome that matches
 * what the caller asked for.
 */
public final class DeviceNames {
    private static final Set<String> RESERVED = Set.of(
            "con", "prn", "aux", "nul",
            "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
            "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9"
    );

    private DeviceNames() {
    }

    public static boolean isWindows() {
        return isWindows(System.getProperty("os.name", ""));
    }

    static boolean isWindows(String osName) {
        return osName.toLowerCase(Locale.ROOT).contains("win");
    }

    /** @return the reserved name a segment spells, or null when the path names no device */
    public static String reservedSegment(Path path) {
        return reservedSegment(path, System.getProperty("os.name", ""));
    }

    static String reservedSegment(Path path, String osName) {
        if (path == null || !isWindows(osName)) return null;
        for (Path segment : path) {
            String reserved = reservedName(segment.toString());
            if (reserved != null) return reserved;
        }
        return null;
    }

    /**
     * An extension does not make a device name safe: {@code NUL.txt} reaches the same device as
     * {@code NUL}. Trailing spaces and dots are stripped by the filesystem before the name is
     * resolved, so they do not either.
     */
    static String reservedName(String segment) {
        String trimmed = segment;
        while (!trimmed.isEmpty()) {
            char last = trimmed.charAt(trimmed.length() - 1);
            if (last != ' ' && last != '.') break;
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        int dot = trimmed.indexOf('.');
        String base = dot < 0 ? trimmed : trimmed.substring(0, dot);
        return RESERVED.contains(base.toLowerCase(Locale.ROOT)) ? base : null;
    }
}
