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
        // Before the user-scope exit: a UNC path is another machine, which no scope below
        // unrestricted reaches. Leaving it until after made it free at the Windows default.
        if (UNC.matcher(command).find()) {
            return "shell scope " + cliName(scope) + " does not reach UNC paths; they name "
                    + "another machine, not this one. Opt in with --shell-scope unrestricted.";
        }
        // Before the user-scope exit, for the same reason as the UNC rule above it: reading
        // anywhere on this machine is the point of user scope, and a credential store is the one
        // carve-out from it. Sitting below the exit made every store free at the scope people
        // actually run - "type ~/.ssh/id_rsa" passed without a question.
        String store = namedSecretStore(command, osName);
        if (store != null) {
            return "shell scope " + cliName(scope) + " does not reach credential stores (" + store
                    + "); ask the user to read it, or move the workspace there deliberately";
        }
        if (scope == ShellScope.USER) return null;
        if (ReadOnlyCommand.isReadOnly(command, osName) && !UNC.matcher(command).find()) return null;
        if (PARENT.matcher(command).find()
                || (!windows && UNIX_ABSOLUTE.matcher(command).find())
                || WINDOWS_ABSOLUTE.matcher(command).find()
                || UNC.matcher(command).find()
                || command.contains("~")
                || lower.contains("$home")
                || lower.contains("${home}")
                || namesAnExpansion(lower)
                || DIRECTORY_CHANGE.matcher(command).find()) {
            return "shell scope workspace blocks explicit paths or directory changes outside "
                    + "the workspace; use scoped file tools or opt in with --shell-scope user";
        }
        return null;
    }

    /**
     * The same variables, however the shell in the command spells them. The list was written in
     * cmd's notation, so at workspace scope "copy x %USERPROFILE%\taken.txt" was held while
     * "powershell -c \"Set-Content $env:USERPROFILE\taken.txt x\"" wrote there unrefused - and
     * workspace scope exists precisely to stop that write.
     */
    private static boolean namesAnExpansion(String lower) {
        for (String expansion : WINDOWS_EXPANSIONS) {
            if (lower.contains(expansion)) return true;
            String name = expansion.substring(1, expansion.length() - 1);
            if (lower.contains("$env:" + name) || lower.contains("${env:" + name)) return true;
        }
        // The .NET way to the same directories, which names none of the variables above.
        return lower.contains("[environment]::getfolderpath")
                || lower.contains("[environment]::getenvironmentvariable");
    }

    /**
     * A credential store named anywhere in the command line, which the shell would otherwise walk
     * past.
     */
    private static String namedSecretStore(String command, String osName) {
        boolean windows = osName.toLowerCase(Locale.ROOT).contains("win");
        // Windows path spelling is case-insensitive, so matching the command case-sensitively was
        // matching a spelling nobody is obliged to use: "%appdata%\microsoft\protect" walked past.
        String haystack = windows ? command.toLowerCase(Locale.ROOT) : command;
        for (java.nio.file.Path store : dev.pironi.safety.SecretStores.stores(osName)) {
            String full = store.toString();
            if (haystack.contains(windows ? full.toLowerCase(Locale.ROOT) : full)) return full;
            java.nio.file.Path fileName = store.getFileName();
            if (fileName == null) continue;
            String name = fileName.toString();
            if (windows) name = name.toLowerCase(Locale.ROOT);
            for (String prefix : new String[]{"~/", "~\\", "/", "\\", "%userprofile%\\"}) {
                if (haystack.contains((windows ? prefix : prefix.toUpperCase(Locale.ROOT)) + name)) {
                    return full;
                }
            }
        }
        return storeUnderAPathToken(command);
    }

    /**
     * Matching text can only catch spellings we thought of, and Windows has one we cannot derive:
     * the 8.3 alias, whose "~1" is an index into whatever else collided in that directory. So each
     * token that names something on disk is put to the file system, which knows where it lands.
     * A guard over the words in a command line, not a parser for them.
     */
    private static String storeUnderAPathToken(String command) {
        for (String token : command.split("[\\s\"'|&;<>()]+")) {
            if (token.length() < 3) continue;
            try {
                java.nio.file.Path store =
                        dev.pironi.safety.SecretStores.protectedBy(java.nio.file.Path.of(token));
                if (store != null) return store.toString();
            } catch (RuntimeException notAPath) {
                // A switch or an argument, not a path. Nothing to check.
            }
        }
        return null;
    }


    private static String cliName(ShellScope scope) {
        return scope.name().toLowerCase(Locale.ROOT);
    }
}
