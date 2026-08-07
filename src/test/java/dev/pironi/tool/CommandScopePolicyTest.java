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
}
