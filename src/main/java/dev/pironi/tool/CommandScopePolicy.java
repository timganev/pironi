package dev.pironi.tool;

import java.util.Locale;
import java.util.regex.Pattern;

/** Conservative lexical guardrail for shell commands; not an OS sandbox. */
final class CommandScopePolicy {
    private static final Pattern PARENT = Pattern.compile("(^|[\\s/\\\\])\\.\\.($|[\\s/\\\\])");
    private static final Pattern UNIX_ABSOLUTE = Pattern.compile("(^|[\\s'\"=])/(?!/)");
    private static final Pattern WINDOWS_ABSOLUTE = Pattern.compile(
            "(?i)(^|[\\s'\"=])[a-z]:[\\\\/]"
    );
    private static final Pattern DIRECTORY_CHANGE = Pattern.compile(
            "(?i)(^|[;&|]\\s*)(cd|pushd|popd)(\\s|$)"
    );

    private CommandScopePolicy() {}

    static String rejection(String command, ShellScope scope) {
        if (scope == ShellScope.UNRESTRICTED) return null;
        String lower = command.toLowerCase(Locale.ROOT);
        if (lower.matches("(?s).*(^|[;&|]\\s*)sudo(\\s|$).*$")) {
            return "shell scope " + cliName(scope) + " blocks sudo";
        }
        if (scope == ShellScope.USER) return null;
        if (PARENT.matcher(command).find()
                || UNIX_ABSOLUTE.matcher(command).find()
                || WINDOWS_ABSOLUTE.matcher(command).find()
                || command.contains("~")
                || lower.contains("$home")
                || lower.contains("${home}")
                || lower.contains("%userprofile%")
                || DIRECTORY_CHANGE.matcher(command).find()) {
            return "shell scope workspace blocks explicit paths or directory changes outside "
                    + "the workspace; use scoped file tools or opt in with --shell-scope user";
        }
        return null;
    }

    private static String cliName(ShellScope scope) {
        return scope.name().toLowerCase(Locale.ROOT);
    }
}
