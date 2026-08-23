package dev.pironi.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class CommandScopePolicyTest {
    @Test
    void workspaceBlocksExplicitEscapesButAllowsProjectCommands() {
        // The absolute-path rule is Unix-only by design: on Windows every cmd.exe switch reads
        // as a path ("dir /b", "tasklist /FO CSV"), so applying it there rejected almost every
        // native command. The platform is named here rather than inherited from the host, which
        // made this assertion pass on Linux and fail on the Windows runner for that same reason.
        assertNull(CommandScopePolicy.rejection("mvn test", ShellScope.WORKSPACE, "Linux"));
        // Reading is unrestricted on this machine; the boundary is on writing.
        assertNull(CommandScopePolicy.rejection("cat /etc/passwd", ShellScope.WORKSPACE, "Linux"));
        assertNull(CommandScopePolicy.rejection("cat ../secret", ShellScope.WORKSPACE));
        assertNotNull(CommandScopePolicy.rejection("cp x /etc/passwd", ShellScope.WORKSPACE, "Linux"));
        assertNotNull(CommandScopePolicy.rejection("cat ../secret > here", ShellScope.WORKSPACE));
        assertNotNull(CommandScopePolicy.rejection("cd subdir", ShellScope.WORKSPACE));
    }

    /**
     * A path rooted at the current drive is as absolute as one with a drive letter, and matched
     * neither Windows rule: one wants {@code C:}, the other wants two backslashes. At workspace
     * scope the write landed outside the workspace and the run reported it as done.
     */
    @Test
    void workspaceBlocksAPathRootedAtTheCurrentDrive() {
        assertNotNull(CommandScopePolicy.rejection(
                "echo out > \\Users\\me\\escaped.txt", ShellScope.WORKSPACE, "Windows 11"));
        assertNotNull(CommandScopePolicy.rejection(
                "copy build.log \\temp\\", ShellScope.WORKSPACE, "Windows 11"));
        // The rule is Windows-only: on Unix a lone backslash is an escape character, not a root.
        assertNull(CommandScopePolicy.rejection(
                "grep -rn 'a\\.b' src", ShellScope.WORKSPACE, "Linux"));
        // cmd.exe switches still are not paths, and a relative write is still allowed.
        assertNull(CommandScopePolicy.rejection("dir /b", ShellScope.WORKSPACE, "Windows 11"));
        assertNull(CommandScopePolicy.rejection(
                "echo out > build\\out.txt", ShellScope.WORKSPACE, "Windows 11"));
    }

    @Test
    void broaderScopesAreExplicit() {
        assertNull(CommandScopePolicy.rejection("cat /home/tim/file", ShellScope.USER));
        assertNotNull(CommandScopePolicy.rejection("sudo true", ShellScope.USER));
        assertNull(CommandScopePolicy.rejection("sudo true", ShellScope.UNRESTRICTED));
    }

    // The guardrail was written against a Unix command line. The Windows spellings of the same
    // escapes reached none of the patterns above, so the workspace scope was materially weaker
    // there than the documentation claims.

    @Test
    void workspaceBlocksWindowsDriveAndUncPaths() {
        // Reading a local path is allowed now; writing to one is not.
        assertNull(CommandScopePolicy.rejection(
                "type C:\\Users\\me\\secret.txt", ShellScope.WORKSPACE, "Windows 11"));
        assertNotNull(CommandScopePolicy.rejection(
                "copy secret.txt C:\\Users\\me\\", ShellScope.WORKSPACE, "Windows 11"));
        // A UNC path is not just outside the workspace, it is off the machine.
        assertNotNull(CommandScopePolicy.rejection(
                "type \\\\fileserver\\share\\secret.txt", ShellScope.WORKSPACE, "Windows 11"));
        assertNotNull(CommandScopePolicy.rejection(
                "copy \"\\\\fileserver\\share\\x\" .", ShellScope.WORKSPACE, "Windows 11"));
        // User scope widens the boundary to this machine, which is the one thing a UNC path is
        // not. This used to be allowed, because the check sat after the user-scope exit.
        assertNotNull(CommandScopePolicy.rejection(
                "type \\\\fileserver\\share\\x", ShellScope.USER, "Windows 11"));
    }

    @Test
    void workspaceBlocksWindowsEnvironmentExpansions() {
        for (String expansion : new String[]{
                "%USERPROFILE%", "%HOMEPATH%", "%HOMEDRIVE%", "%APPDATA%", "%LOCALAPPDATA%",
                "%PROGRAMDATA%", "%SYSTEMROOT%", "%WINDIR%", "%PUBLIC%", "%TEMP%", "%TMP%"
        }) {
            // Reading through an expansion is allowed; writing through one is not.
            assertNull(
                    CommandScopePolicy.rejection("type " + expansion + "\\x", ShellScope.WORKSPACE, "Windows 11"),
                    expansion + " names a local path, and reading it is permitted"
            );
            assertNotNull(
                    CommandScopePolicy.rejection("copy x " + expansion + "\\x", ShellScope.WORKSPACE, "Windows 11"),
                    expansion + " reaches outside the workspace and must not be written"
            );
        }
    }

    @Test
    void everyScopeBelowUnrestrictedBlocksWindowsElevation() {
        // sudo was blocked from the start; runas is the same act with the other spelling.
        for (ShellScope scope : new ShellScope[]{ShellScope.WORKSPACE, ShellScope.USER}) {
            assertNotNull(CommandScopePolicy.rejection("runas /user:Administrator cmd", scope, "Windows 11"));
            assertNotNull(CommandScopePolicy.rejection("gsudo powershell", scope, "Windows 11"));
            assertNotNull(CommandScopePolicy.rejection("echo hi && runas /user:x cmd", scope, "Windows 11"));
        }
        assertNull(CommandScopePolicy.rejection("runas /user:x cmd", ShellScope.UNRESTRICTED, "Windows 11"));
    }

    @Test
    void readingReachesAnywhereAndWritingDoesNot() {
        // Writing is what the workspace boundary protects. A command that provably only reads
        // may name any path, so the shell reaches as far as the file tools do.
        assertNull(CommandScopePolicy.rejection(
                "cat /etc/hosts", ShellScope.WORKSPACE, "Mac OS X"));
        assertNull(CommandScopePolicy.rejection(
                "grep -n root /etc/passwd", ShellScope.WORKSPACE, "Mac OS X"));

        assertNotNull(CommandScopePolicy.rejection(
                "cat /etc/passwd > stolen.txt", ShellScope.WORKSPACE, "Mac OS X"));
        assertNotNull(CommandScopePolicy.rejection(
                "echo hi > /etc/hosts", ShellScope.WORKSPACE, "Mac OS X"));
        assertNotNull(CommandScopePolicy.rejection(
                "sed -i '' 's/a/b/' /etc/hosts", ShellScope.WORKSPACE, "Mac OS X"));
        assertNotNull(CommandScopePolicy.rejection(
                "sh -c 'cat /etc/passwd'", ShellScope.WORKSPACE, "Mac OS X"));
        assertNotNull(CommandScopePolicy.rejection(
                "sudo cat /etc/shadow", ShellScope.WORKSPACE, "Mac OS X"));
    }

    @Test
    void aPatternIsNotAPath() {
        // Every sed address and awk pattern opens with a slash after a quote, which is exactly
        // what the absolute-path rule looks for. Refusing them sent one run to read a 143 KB
        // file whole instead of cutting out the section it wanted.
        assertNull(CommandScopePolicy.rejection(
                "sed -n '/^## .*ANF-4467/,/^## [^#]/p' ACTIVE.md", ShellScope.WORKSPACE, "Mac OS X"));
        assertNull(CommandScopePolicy.rejection(
                "awk '/^## /{print}' ACTIVE.md", ShellScope.WORKSPACE, "Mac OS X"));

        // A real path is still a real path, wherever it is quoted.
        assertNotNull(CommandScopePolicy.rejection(
                "cp x '/etc/passwd'", ShellScope.WORKSPACE, "Mac OS X"));
        assertNotNull(CommandScopePolicy.rejection(
                "sh -c 'cat /etc/passwd'", ShellScope.WORKSPACE, "Mac OS X"));
        // Reading is free, but /etc/shadow is a credential store and stays out of reach.
        assertNotNull(CommandScopePolicy.rejection(
                "sed -n '/x/p' /etc/shadow", ShellScope.WORKSPACE, "Linux"));
        assertNull(CommandScopePolicy.rejection(
                "sed -n '/x/p' /etc/hosts", ShellScope.WORKSPACE, "Mac OS X"));
    }

    @Test
    void uncPathsAreRefusedAtUserScopeAsWellAsWorkspace() {
        // User scope widens the boundary to this machine, and a UNC path names a different one.
        // The check used to sit after the user-scope exit, which left it free at the Windows
        // portable default of --shell-scope user.
        String unc = "type \\\\localhost\\c$\\Windows\\win.ini";
        assertNotNull(CommandScopePolicy.rejection(unc, ShellScope.WORKSPACE, "Windows 11"));
        assertNotNull(CommandScopePolicy.rejection(unc, ShellScope.USER, "Windows 11"));
        assertNotNull(CommandScopePolicy.rejection(
                "dir \\\\server\\share", ShellScope.USER, "Windows 11"));
        // Unrestricted is the deliberate opt-in and still reaches it.
        assertNull(CommandScopePolicy.rejection(unc, ShellScope.UNRESTRICTED, "Windows 11"));
        // A lone backslash is not a UNC prefix and must not be caught by it.
        assertNull(CommandScopePolicy.rejection(
                "dir sub\\folder", ShellScope.USER, "Windows 11"));
    }

    @Test
    void ordinaryWindowsCommandsStillRun() {
        assertNull(CommandScopePolicy.rejection("mvnw.cmd test", ShellScope.WORKSPACE, "Windows 11"));
        assertNull(CommandScopePolicy.rejection("gradlew.bat build", ShellScope.WORKSPACE, "Windows 11"));
        // A cmd switch is not a path. The Unix rule read every one of them as an absolute path,
        // so "dir /b", "findstr /s" and "tasklist /FO CSV" were all rejected on Windows.
        assertNull(CommandScopePolicy.rejection("dir /b src", ShellScope.WORKSPACE, "Windows 11"));
        assertNull(CommandScopePolicy.rejection(
                "findstr /s TODO *.java", ShellScope.WORKSPACE, "Windows 11"));
        assertNull(CommandScopePolicy.rejection(
                "tasklist /FO CSV /NH", ShellScope.WORKSPACE, "Windows 11"));
        // The Unix rule still applies where "/" starts a path and the command is not a reader.
        assertNotNull(CommandScopePolicy.rejection("cp x /etc/passwd", ShellScope.WORKSPACE, "Linux"));
        assertNotNull(CommandScopePolicy.rejection(
                "copy x C:\\Windows\\x", ShellScope.WORKSPACE, "Windows 11"));
    }

    @Test
    void aCredentialStoreIsOutOfReachAtUserScopeAsWellAsWorkspace() {
        // The check sat below the user-scope exit, so at the scope people actually run the shell
        // walked into a key without a question. Same shape as the UNC rule two commits earlier.
        // ~/.ssh is on the list for every platform, so this holds wherever the tests run.
        assertNotNull(CommandScopePolicy.rejection(
                "type C:\\Users\\me\\.ssh\\id_rsa", ShellScope.USER, "Windows 11"));
        assertNotNull(CommandScopePolicy.rejection(
                "cat ~/.ssh/id_rsa", ShellScope.USER, "Linux"));
        // Unrestricted is the deliberate opt-out and still is.
        assertNull(CommandScopePolicy.rejection(
                "type C:\\Users\\me\\.ssh\\id_rsa", ShellScope.UNRESTRICTED, "Windows 11"));
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void aWindowsStoreIsNamedWhicheverWayItIsSpelled() {
        // The Windows stores are resolved from %APPDATA% and %LOCALAPPDATA%, which exist only on
        // Windows: asking for them elsewhere measures the runner's environment, not this rule.
        // Windows path spelling is case-insensitive; matching the command case-sensitively was
        // matching a spelling nobody is obliged to type.
        assertNotNull(CommandScopePolicy.rejection(
                "type %APPDATA%\\Microsoft\\Protect\\key", ShellScope.USER, "Windows 11"));
        assertNotNull(CommandScopePolicy.rejection(
                "type %appdata%\\microsoft\\protect\\key", ShellScope.USER, "Windows 11"));
        // "Application Data" and "Local Settings" are junctions kept from XP, and SSH~1 is the
        // 8.3 alias; all three are real routes on a stock install, spelled against this machine's
        // own home so the file-system check has something to resolve.
        String home = System.getProperty("user.home");
        assertNotNull(CommandScopePolicy.rejection(
                "type " + home + "\\Application Data\\Microsoft\\Protect\\key",
                ShellScope.USER, "Windows 11"));
        assertNotNull(CommandScopePolicy.rejection(
                "dir " + home + "\\Local Settings\\Microsoft\\Credentials",
                ShellScope.USER, "Windows 11"));
        // The 8.3 alias of a store directory - "SSH~1" for .ssh - is deliberately not asserted
        // here. It exists only where that directory does, and the store list this rule reads comes
        // from the real user.home, which a test must not write into. Asserting it passed on a
        // developer machine that happens to have ~/.ssh and failed on a runner that does not.
        // SecretStoresTest covers the resolution itself, on a temp home it controls.
    }

    @Test
    void workspaceScopeHoldsWhicheverShellSpellsTheVariable() {
        // The expansion list was written in cmd's notation. A PowerShell command reaching the
        // same directory named none of it, so at the tightest scope - the one whose whole job is
        // to keep writes inside the sandbox - this wrote to the home directory unrefused.
        assertNotNull(CommandScopePolicy.rejection(
                "powershell -c \"Set-Content $env:USERPROFILE\\taken.txt x\"",
                ShellScope.WORKSPACE, "Windows 11"));
        assertNotNull(CommandScopePolicy.rejection(
                "powershell -c \"Remove-Item ${env:APPDATA}\\something\"",
                ShellScope.WORKSPACE, "Windows 11"));
        assertNotNull(CommandScopePolicy.rejection(
                "powershell -c \"Copy-Item a.txt $env:HOMEDRIVE$env:HOMEPATH\"",
                ShellScope.WORKSPACE, "Windows 11"));
        // The .NET route to the same places names none of the variables at all.
        assertNotNull(CommandScopePolicy.rejection(
                "powershell -c \"Set-Content ([Environment]::GetFolderPath('UserProfile')) x\"",
                ShellScope.WORKSPACE, "Windows 11"));
        // cmd's spelling was already held and still is.
        assertNotNull(CommandScopePolicy.rejection(
                "copy x.txt %USERPROFILE%\\taken.txt", ShellScope.WORKSPACE, "Windows 11"));
        // A variable that names no directory outside the workspace is not the point.
        assertNull(CommandScopePolicy.rejection(
                "powershell -c \"$env:PATH\"", ShellScope.WORKSPACE, "Windows 11"));
        // User scope deliberately reaches the whole machine, and still does.
        assertNull(CommandScopePolicy.rejection(
                "powershell -c \"Set-Content $env:USERPROFILE\\notes.txt x\"",
                ShellScope.USER, "Windows 11"));
    }

    @Test
    void outlookDataIsNotACredentialStore() {
        // The list is named on purpose rather than derived from "hidden": AppData carries the
        // hidden attribute on Windows, so a rule by hiddenness would lock away exactly this.
        assertNull(CommandScopePolicy.rejection(
                "dir C:\\Users\\me\\AppData\\Local\\Microsoft\\Outlook",
                ShellScope.USER, "Windows 11"));
        assertNull(CommandScopePolicy.rejection(
                "dir C:\\Users\\me\\Documents", ShellScope.USER, "Windows 11"));
    }
}
