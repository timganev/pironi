package dev.pironi.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class CommandScopePolicyTest {
    @Test
    void workspaceBlocksExplicitEscapesButAllowsProjectCommands() {
        assertNull(CommandScopePolicy.rejection("mvn test", ShellScope.WORKSPACE));
        assertNotNull(CommandScopePolicy.rejection("cat /etc/passwd", ShellScope.WORKSPACE));
        assertNotNull(CommandScopePolicy.rejection("cat ../secret", ShellScope.WORKSPACE));
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
        assertNotNull(CommandScopePolicy.rejection(
                "type C:\\Users\\me\\secret.txt", ShellScope.WORKSPACE, "Windows 11"));
        // A UNC path is not just outside the workspace, it is off the machine.
        assertNotNull(CommandScopePolicy.rejection(
                "type \\\\fileserver\\share\\secret.txt", ShellScope.WORKSPACE, "Windows 11"));
        assertNotNull(CommandScopePolicy.rejection(
                "copy \"\\\\fileserver\\share\\x\" .", ShellScope.WORKSPACE, "Windows 11"));
        assertNull(CommandScopePolicy.rejection(
                "type \\\\fileserver\\share\\x", ShellScope.USER, "Windows 11"));
    }

    @Test
    void workspaceBlocksWindowsEnvironmentExpansions() {
        for (String expansion : new String[]{
                "%USERPROFILE%", "%HOMEPATH%", "%HOMEDRIVE%", "%APPDATA%", "%LOCALAPPDATA%",
                "%PROGRAMDATA%", "%SYSTEMROOT%", "%WINDIR%", "%PUBLIC%", "%TEMP%", "%TMP%"
        }) {
            assertNotNull(
                    CommandScopePolicy.rejection("type " + expansion + "\\x", ShellScope.WORKSPACE, "Windows 11"),
                    expansion + " reaches outside the workspace and must be rejected"
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
        // The Unix rule still applies where "/" does start a path.
        assertNotNull(CommandScopePolicy.rejection("cat /etc/passwd", ShellScope.WORKSPACE, "Linux"));
        assertNotNull(CommandScopePolicy.rejection(
                "type C:\\Windows\\x", ShellScope.WORKSPACE, "Windows 11"));
    }
}
