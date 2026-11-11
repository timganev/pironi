package dev.pironi.cli;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SystemClipboardTest {
    @Test
    void waylandIsAskedFirstAndX11StaysAsTheFallback() {
        assertEquals(
                List.of(
                        List.of("wl-paste", "--no-newline", "--type", "text"),
                        List.of("xclip", "-selection", "clipboard", "-o")),
                SystemClipboard.commands("Linux", "wayland-0"));
        assertEquals(
                List.of(List.of("xclip", "-selection", "clipboard", "-o")),
                SystemClipboard.commands("Linux", null));
    }

    @Test
    void macReadsItsOwnClipboardAndWindowsLeavesCtrlVToTheTerminal() {
        assertEquals(List.of(List.of("pbpaste")), SystemClipboard.commands("Mac OS X", null));
        assertEquals(List.of(), SystemClipboard.commands("Windows 11", null));
    }

    @Test
    void lineBreaksBecomeTheOnesABracketedPasteLeaves() {
        assertEquals("one\ntwo\nthree", SystemClipboard.normalized("one\r\ntwo\rthree"));
    }
}
