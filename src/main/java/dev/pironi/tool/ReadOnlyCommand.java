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

    /**
     * Read-only git subcommands; everything else in git can write.
     *
     * <p>branch, tag and remote were here and are not: {@code git branch -D}, {@code git tag -d}
     * and {@code git remote add} all write, and being classified read-only skipped the approval
     * prompt outright. They are read-only only when listing, which {@link #gitOnlyReads} decides.
     */
    private static final Set<String> GIT_READERS = Set.of(
            "log", "status", "diff", "show", "blame", "describe", "rev-parse",
            "ls-files", "ls-tree", "shortlog", "cat-file", "grep", "whatchanged"
    );

    /** Read-only until given a flag, and the flags that write are the short ones. */
    private static final Set<String> GIT_LISTERS = Set.of("branch", "tag", "remote");

    /**
     * awk runs a shell through {@code system()} and through a pipe to a command string. Neither is
     * a shell metacharacter as far as the check above is concerned, because the shell never sees
     * them either - the whole script arrives as one quoted argument.
     */
    private static final Pattern AWK_ESCAPE = Pattern.compile(
            "\\bsystem\\s*\\(|\\|\\s*&?\\s*[\"']|[\"']\\s*\\|\\s*&?\\s*getline"
    );

    /**
     * GNU sed's {@code e} executes its argument and {@code w}/{@code W} write a file. A sed
     * command carries its address in front of it - {@code 1e}, {@code $w}, {@code /re/w} - so what
     * precedes the letter is a digit or a delimiter rather than a space, which is what let
     * {@code sed '1e touch pwned'} read as a reader. {@code -e} is excluded by leaving {@code -}
     * out of the set: it is the script flag, not the execute command.
     */
    private static final Pattern SED_ESCAPE = Pattern.compile(
            "(?:^|['\"\\s;{}0-9$/,])[ewW](?:\\s|$)"
    );

    /** Anything that turns a reader into a writer, or hides another program inside. */
    private static final Pattern ESCAPE = Pattern.compile("[>`]|\\$\\(|<\\(|\\btee\\b|\\bxargs\\b");

    /** Discarding output is not writing. */
    private static final Pattern DISCARD =
            Pattern.compile("\\d?>>?\\s*(/dev/null|(?i:nul))(?=\\s|$)");

    /**
     * A newline separates two commands exactly as {@code ;} does, and was missing here. The
     * classifier read {@code "cat notes.txt\nrm -f notes.txt"} as one call to a reader with odd
     * arguments and returned true, so the command ran with no approval prompt and the file was
     * gone. bash executes both halves; cmd.exe stops at the newline, which is why this only ever
     * showed on the platforms CI runs and nobody develops on.
     */
    private static final Pattern SEPARATOR = Pattern.compile("&&|\\|\\||;|\\||&|\\r|\\n");

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
        // sed and awk are text tools with a shell inside them, and the script is one quoted
        // argument, so nothing above this line can see what it asks for.
        if (program.equals("awk") && AWK_ESCAPE.matcher(segment).find()) return false;
        if (program.equals("sed") && SED_ESCAPE.matcher(segment).find()) return false;
        if (!windows && program.equals("find") && segment.matches(
                ".*\\s-(delete|exec|execdir|ok|okdir|fls|fprint|fprintf)\\b.*")) return false;
        if (program.equals("git")) {
            String subcommand = words.length > 1 ? words[1].toLowerCase(Locale.ROOT) : "";
            return gitOnlyReads(subcommand, words);
        }
        return true;
    }

    /**
     * {@code git branch}, {@code git tag} and {@code git remote} list until they are given
     * something to do. Anything past the subcommand that is not a plain listing switch could be
     * {@code -D}, {@code -d}, {@code add} or {@code set-url}, so only the bare forms and the
     * listing flags read.
     */
    private static boolean gitOnlyReads(String subcommand, String[] words) {
        if (GIT_READERS.contains(subcommand)) return true;
        if (!GIT_LISTERS.contains(subcommand)) return false;
        for (int index = 2; index < words.length; index++) {
            String argument = words[index].toLowerCase(Locale.ROOT);
            if (argument.isEmpty()) continue;
            boolean listing = argument.equals("-l") || argument.equals("--list")
                    || argument.equals("-v") || argument.equals("-vv") || argument.equals("--verbose")
                    || argument.equals("show") || argument.equals("get-url")
                    || argument.equals("--all") || argument.equals("--merged")
                    || argument.equals("--no-merged") || argument.equals("--contains")
                    || argument.equals("--sort") || argument.startsWith("--sort=")
                    || argument.equals("--format") || argument.startsWith("--format=");
            if (!listing) return false;
        }
        return true;
    }
}
