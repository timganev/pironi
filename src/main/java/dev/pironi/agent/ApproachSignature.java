package dev.pironi.agent;

import java.util.stream.Collectors;

/**
 * What one attempt is an attempt at, as a string that repeats when the idea repeats.
 *
 * <p>A parser of command lines with no state of its own, which is why it is here and not inside
 * the loop: reaching an eighteen-line method meant reading past thirteen hundred lines of turn
 * handling twice in one day.
 */
public final class ApproachSignature {
    private ApproachSignature() {
    }

    /**
     * Programs that can do anything, so failures say nothing about the next call: interpreters, and
     * search tools too - walling off "find" cost a run the one directory it had not tried.
     */
    private static final java.util.Set<String> GENERAL_PURPOSE_PROGRAMS = java.util.Set.of(
            "python", "python3", "bash", "sh", "zsh", "perl", "ruby", "node",
            "find", "grep", "egrep", "fgrep", "rg", "mdfind", "strings"
    );

    /** Words that position a command rather than being one. */
    private static final java.util.Set<String> COMMAND_PREFIXES = java.util.Set.of(
            // Bash and cmd.exe both position with cd/pushd/popd; "set" is the cmd assignment and a
            // bash builtin, and export/source/. are bash only but harmless to name on Windows.
            "cd", "pushd", "popd", "export", "set", "unset", "source", "."
    );

    /** Separators on both shells: bash {@code && || ; &}, cmd.exe {@code && || &}. */
    private static final java.util.regex.Pattern SEPARATORS =
            java.util.regex.Pattern.compile("&&|\\|\\||;|&");

    /** Flags after which a search program names the thing it is looking for. */
    private static final java.util.Set<String> TARGET_FLAGS = java.util.Set.of(
            "-name", "-iname", "-path", "-ipath", "-regex", "-iregex", "-e", "--include"
    );

    /** Names the tool, or the program a shell call runs, so rewordings collapse into one approach. */
    public static String approachSignature(ToolCall call) {
        if (!"run_command".equals(call.name())) return call.name();
        var command = call.arguments() == null ? null : call.arguments().get("command");
        if (command == null || !command.isTextual()) return call.name();
        for (String line : command.asText().split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            String segment = firstActingSegment(trimmed);
            String program = programName(segment.split("\\s+")[0]);
            if (program.isEmpty()) return call.name();
            if (!GENERAL_PURPOSE_PROGRAMS.contains(program)) return call.name() + ":" + program;
            String target = commandTarget(segment);
            return target.isEmpty()
                    ? call.name() + ":" + program
                    : call.name() + ":" + program + ":" + target;
        }
        return call.name();
    }
    /** The bare program, with either platform's path separator stripped. */
    private static String programName(String word) {
        int separator = Math.max(word.lastIndexOf('/'), word.lastIndexOf('\\'));
        return separator < 0 ? word : word.substring(separator + 1);
    }
    /** The part of a command line that does the work. */
    public static String firstActingSegment(String line) {
        String[] segments = SEPARATORS.split(line);
        for (String candidate : segments) {
            String segment = candidate.strip();
            if (segment.isEmpty()) continue;
            var substitution = java.util.regex.Pattern
                    .compile("^[A-Za-z_][A-Za-z0-9_]*=\\$\\((.+)\\)$").matcher(segment);
            if (substitution.matches()) return substitution.group(1).strip();
            String word = segment.split("\\s+")[0];
            if (word.contains("=")) continue;
            if (COMMAND_PREFIXES.contains(programName(word))) continue;
            return segment;
        }
        return segments.length == 0 ? line : segments[0].strip();
    }
    /** What a general-purpose program is reaching for. */
    public static String commandTarget(String command) {
        var imports = java.util.regex.Pattern
                .compile("\\bimport\\s+([A-Za-z_][A-Za-z0-9_]*(?:\\s*,\\s*[A-Za-z_][A-Za-z0-9_]*)*)")
                .matcher(command);
        if (imports.find()) {
            // "import json, sqlite3" and "import json, plistlib" are different approaches; keying
            // on the first module alone collapses them and can wall off the interpreter itself.
            return java.util.Arrays.stream(imports.group(1).split("\\s*,\\s*"))
                    .sorted().collect(Collectors.joining("+"));
        }
        String[] words = command.split("\\s+");
        // A search names its subject after a flag; the path it starts from barely changes and would
        // key every search in a tree to the same approach.
        for (int index = 1; index < words.length - 1; index++) {
            if (TARGET_FLAGS.contains(words[index])) return unquote(words[index + 1]);
        }
        for (int index = 1; index < words.length; index++) {
            String word = unquote(words[index]);
            if (word.startsWith("-") || word.isEmpty()) continue;
            int slash = word.lastIndexOf('/');
            return slash < 0 ? word : word.substring(slash + 1);
        }
        return "";
    }
    private static String unquote(String word) {
        return word.replaceAll("^[\"']+", "").replaceAll("[\"']+$", "");
    }
    /** A single-purpose program can be walled off; a bare interpreter cannot. */
    public static boolean blockable(String signature) {
        int colon = signature.indexOf(':');
        if (colon < 0) return true;
        String rest = signature.substring(colon + 1);
        int target = rest.indexOf(':');
        if (target >= 0) return true;
        return !GENERAL_PURPOSE_PROGRAMS.contains(rest);
    }
}
