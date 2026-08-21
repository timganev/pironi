package dev.pironi.status;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatusGlyphsTest {
    private static final String ROW = "● model │ workspace │ ctx ~4% │ ready";

    @Test
    void aUtf8ConsoleKeepsTheRowAsItIs() {
        StatusGlyphs glyphs = new StatusGlyphs(StandardCharsets.UTF_8);

        assertTrue(glyphs.supported());
        assertEquals(ROW, glyphs.downgrade(ROW));
    }

    @Test
    void aConsoleThatCannotCarryThemGetsAscii() {
        // Cp1252 is what a Windows JVM reports for the console even at code page 65001.
        StatusGlyphs glyphs = new StatusGlyphs(Charset.forName("windows-1252"));

        assertFalse(glyphs.supported());
        assertEquals("* model | workspace | ctx ~4% | ready", glyphs.downgrade(ROW));
    }

    @Test
    void marksAndSpinnerFramesDegradeToo() {
        StatusGlyphs glyphs = new StatusGlyphs(StandardCharsets.US_ASCII);

        assertEquals("+ done", glyphs.downgrade("✓ done"));
        assertEquals("x failed", glyphs.downgrade("✗ failed"));
        assertEquals("- read_file", glyphs.downgrade("• read_file"));
        // Every spinner frame must map to something, or the row would stutter on one of them.
        for (String frame : new String[]{"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"}) {
            String downgraded = glyphs.downgrade(frame);
            assertEquals(1, downgraded.length(), frame);
            assertTrue(downgraded.charAt(0) < 128, frame + " became " + downgraded);
        }
    }

    @Test
    void plainTextIsNeverTouched() {
        StatusGlyphs glyphs = new StatusGlyphs(StandardCharsets.US_ASCII);

        assertEquals("ready", glyphs.downgrade("ready"));
        assertEquals("", glyphs.downgrade(""));
        assertEquals(null, glyphs.downgrade(null));
    }
}
