package dev.pironi.tool;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlatformShellTest {
    @Test void buildsBashInvocationOnLinuxAndMac() {
        assertEquals(
                List.of("/bin/bash", "-c", "echo ok"),
                PlatformShell.command("echo ok", "Linux")
        );
        assertEquals("/bin/bash", PlatformShell.name("Mac OS X"));
    }

    @Test void buildsCmdInvocationOnWindows() {
        assertEquals(
                List.of("cmd.exe", "/d", "/s", "/c", "echo ok"),
                PlatformShell.command("echo ok", "Windows 11")
        );
        assertEquals("cmd.exe", PlatformShell.name("Windows 11"));
    }
}
