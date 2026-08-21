package dev.pironi.tool;

import java.util.Locale;
import java.util.regex.Pattern;

/** Conservative lexical guardrail for shell commands; not an OS sandbox. */
final class CommandScopePolicy {
    private static final Pattern PARENT = Pattern.compile("(^|[\\s/\\\\])\\.\\.($|[\\s/\\\\])");
    /** A slash starts a path only when a path character follows. */
    private static final Pattern UNIX_ABSOLUTE =
            Pattern.compile("(^|[\\s'\"=])/(?=[A-Za-z0-9._~-])");
    private static final Pattern WINDOWS_ABSOLUTE = Pattern.compile(
            "(?i)(^|[\\s'\"=])[a-z]:[\\\\/]"
    );
    private static final Pattern DIRECTORY_CHANGE = Pattern.compile(
            "(?i)(^|[;&|]\\s*)(cd|pushd|popd)(\\s|$)"
    );
    /** A UNC path leaves the machine entirely and matches neither of the patterns above. */
    private static final Pattern UNC = Pattern.compile("(^|[\\s'\"=])\\\\\\\\[^\\\\\\s]");
    /** Windows expansions that reach outside the workspace, the counterparts of ~ and $HOME. */
    private static final java.util.List<String> WINDOWS_EXPANSIONS = java.util.List.of(
            "%userprofile%", "%homedrive%", "%homepath%", "%appdata%", "%localappdata%",
            "%programdata%", "%programfiles%", "%systemroot%", "%windir%", "%public%",
            "%temp%", "%tmp%"
    );
    /** Elevation on Windows, the counterpart of sudo. */
    private static final Pattern ELEVATION = Pattern.compile(
            "(?i)(^|[;&|]\\s*)(sudo|runas|gsudo)(\\s|$)"
    );

    private CommandScopePolicy() {}

    static String rejection(String command, ShellScope scope) {
        return rejection(command, scope, System.getProperty("os.name", ""));
    }

    /**
     * The Unix absolute-path rule reads every cmd.exe switch as a path - {@code dir /b}, {@code
     * tasklist /FO CSV} - so on Windows it is applied only where "/" starts a path.
     */
    static String rejection(String command, ShellScope scope, String osName) {
        boolean windows = osName.toLowerCase(Locale.ROOT).contains("win");
        if (scope == ShellScope.UNRESTRICTED) return null;
        String lower = command.toLowerCase(Locale.ROOT);
        if (ELEVATION.matcher(command).find()) {
            return "shell scope " + cliName(scope) + " blocks elevation (sudo, runas)";
        }
        if (scope == ShellScope.USER) return null;
        // Reading anywhere on this machine is allowed; the boundary is on writing.
        String store = namedSecretStore(command, osName);
        if (store != null) {
            return "shell scope " + cliName(scope) + " does not reach credential stores (" + store
                    + "); ask the user to read it, or move the workspace there deliberately";
        }
        if (ReadOnlyCommand.isReadOnly(command, osName) && !UNC.matcher(command).find()) return null;
        if (PARENT.matcher(command).find()
                || (!windows && UNIX_ABSOLUTE.matcher(command).find())
                || WINDOWS_ABSOLUTE.matcher(command).find()
                || UNC.matcher(command).find()
                || command.contains("~")
                || lower.contains("$home")
                || lower.contains("${home}")
                || WINDOWS_EXPANSIONS.stream().anyMatch(lower::contains)
                || DIRECTORY_CHANGE.matcher(command).find()) {
            return "shell scope workspace blocks explicit paths or directory changes outside "
                    + "the workspace; use scoped file tools or opt in with --shell-scope user";
        }
        return null;
    }

    /**
     * A credential store named anywhere in the command line, which the shell would otherwise walk
     * past.
     */
    private static String namedSecretStore(String command, String osName) {
        for (java.nio.file.Path store : dev.pironi.safety.SecretStores.stores(osName)) {
            String full = store.toString();
            if (command.contains(full)) return full;
            java.nio.file.Path fileName = store.getFileName();
            if (fileName == null) continue;
            String name = fileName.toString();
            for (String prefix : new String[]{"~/", "~\\", "/", "\\", "%USERPROFILE%\\"}) {
                if (command.contains(prefix + name)) return full;
            }
        }
        return null;
    }

    private static String cliName(ShellScope scope) {
        return scope.name().toLowerCase(Locale.ROOT);
    }
}
