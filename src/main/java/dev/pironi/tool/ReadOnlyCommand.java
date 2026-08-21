package dev.pironi.tool;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Whether a shell command only reads. Deliberately narrow: wrong here costs one prompt, wrong the
 * other way costs a silent write.
 */
public final class ReadOnlyCommand {
    /** cmd.exe has its own readers, and none of the Unix ones. */
    private static final Set<String> WINDOWS_READERS = Set.of(
            "dir", "type", "findstr", "more", "fc", "where", "tasklist", "tree", "ver",
            "hostname", "whoami", "systeminfo"
    );

    /** Programs that cannot alter anything on their own. */
    private static final Set<String> READERS = Set.of(
            "ls", "cat", "head", "tail", "grep", "egrep", "fgrep", "rg", "wc", "file", "stat",
            "sort", "uniq", "cut", "tr", "echo", "pwd", "date", "which", "type", "basename",
            "dirname", "du", "df", "diff", "cmp", "jq", "column", "printf", "seq", "nl", "od",
            "xxd", "strings", "gunzip", "zcat", "sed", "awk", "find", "git", "true", "false"
    );

    /** Read-only git subcommands; everything else in git can write. */
    private static final Set<String> GIT_READERS = Set.of(
            "log", "status", "diff", "show", "blame", "branch", "describe", "rev-parse",
            "ls-files", "ls-tree", "shortlog", "cat-file", "grep", "tag", "remote", "whatchanged"
    );

    /** Anything that turns a reader into a writer, or hides another program inside. */
    private static final Pattern ESCAPE = Pattern.compile("[>`]|\\$\\(|<\\(|\\btee\\b|\\bxargs\\b");

    /** Discarding output is not writing. */
    private static final Pattern DISCARD =
            Pattern.compile("\\d?>>?\\s*(/dev/null|(?i:nul))(?=\\s|$)");

    private static final Pattern SEPARATOR = Pattern.compile("&&|\\|\\||;|\\||&");

    private ReadOnlyCommand() {
    }

    public static boolean isReadOnly(String command) {
        return isReadOnly(command, System.getProperty("os.name", ""));
    }

    static boolean isReadOnly(String command, String osName) {
        if (command == null || command.isBlank()) return false;
        String withoutDiscards = DISCARD.matcher(command).replaceAll(" ");
        if (ESCAPE.matcher(withoutDiscards).find()) return false;
        boolean windows = osName.toLowerCase(Locale.ROOT).contains("win");
        for (String segment : SEPARATOR.split(withoutDiscards)) {
            if (!segmentOnlyReads(segment.strip(), windows)) return false;
        }
        return true;
    }

    private static boolean segmentOnlyReads(String segment, boolean windows) {
        if (segment.isEmpty()) return false;
        String[] words = segment.split("\\s+");
        String program = words[0];
        int slash = Math.max(program.lastIndexOf('/'), program.lastIndexOf('\\'));
        if (slash >= 0) program = program.substring(slash + 1);
        program = program.toLowerCase(Locale.ROOT);
        if (windows) {
            if (program.endsWith(".exe")) program = program.substring(0, program.length() - 4);
            // find on Windows is a text search, not the Unix walker, and has no -delete.
            if (WINDOWS_READERS.contains(program) || program.equals("find")) return true;
        }
        if (!READERS.contains(program)) return false;

        // sed -i edits in place, and find can delete or run anything it likes.
        if (program.equals("sed") && segment.matches(".*\\s-[a-zA-Z]*i.*")) return false;
        if (!windows && program.equals("find") && segment.matches(
                ".*\\s-(delete|exec|execdir|ok|okdir|fls|fprint|fprintf)\\b.*")) return false;
        if (program.equals("git")) {
            String subcommand = words.length > 1 ? words[1].toLowerCase(Locale.ROOT) : "";
            return GIT_READERS.contains(subcommand);
        }
        return true;
    }
}
