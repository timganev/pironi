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
}
