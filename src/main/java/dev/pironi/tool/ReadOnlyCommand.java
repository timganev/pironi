package dev.pironi.tool;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Whether a shell command only reads.
 *
 * <p>Every command asked for approval, including the ones that only look. A user answering "y"
 * to {@code grep -n 'ANF-4467' ACTIVE.md} learns nothing and is trained to answer "y" without
 * reading, which is worse than not asking: the prompts that matter arrive in the same stream as
 * the ones that never did.
 *
 * <p>The test is deliberately narrow. Anything that could reach a program not on the list -
 * a substitution, a redirection, xargs - is treated as writing, because a shell can hide a
 * write almost anywhere. Being wrong here means one extra prompt; being wrong the other way
 * means a silent write.
 */
public final class ReadOnlyCommand {
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

    private static final Pattern SEPARATOR = Pattern.compile("&&|\\|\\||;|\\||&");

    private ReadOnlyCommand() {
    }

    public static boolean isReadOnly(String command) {
        if (command == null || command.isBlank()) return false;
        if (ESCAPE.matcher(command).find()) return false;
        for (String segment : SEPARATOR.split(command)) {
            if (!segmentOnlyReads(segment.strip())) return false;
        }
        return true;
    }

    private static boolean segmentOnlyReads(String segment) {
        if (segment.isEmpty()) return false;
        String[] words = segment.split("\\s+");
        String program = words[0];
        int slash = Math.max(program.lastIndexOf('/'), program.lastIndexOf('\\'));
        if (slash >= 0) program = program.substring(slash + 1);
        program = program.toLowerCase(Locale.ROOT);
        if (!READERS.contains(program)) return false;

        // sed -i edits in place, and find can delete or run anything it likes.
        if (program.equals("sed") && segment.matches(".*\\s-[a-zA-Z]*i.*")) return false;
        if (program.equals("find") && segment.matches(
                ".*\\s-(delete|exec|execdir|ok|okdir|fls|fprint|fprintf)\\b.*")) return false;
        if (program.equals("git")) {
            String subcommand = words.length > 1 ? words[1].toLowerCase(Locale.ROOT) : "";
            return GIT_READERS.contains(subcommand);
        }
        return true;
    }
}
