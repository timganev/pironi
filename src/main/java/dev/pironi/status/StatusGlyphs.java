package dev.pironi.status;

import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;

/**
 * Box and mark characters for the status row, downgraded when the console cannot carry them.
 *
 * <p>The row is drawn with a vertical bar, a bullet, a gear, tick and cross marks and a braille
 * spinner. A console whose encoding has none of them prints substitutes chosen by whatever sits
 * in the way - one Windows terminal rendered every separator as a bare "x" - so the row becomes
 * unreadable in a manner nothing in the code accounts for. Deciding here makes the outcome one of
 * two known shapes rather than an open question.
 */
final class StatusGlyphs {
    /** Each decorative character with the ASCII it degrades to. */
    private static final String[][] SUBSTITUTIONS = {
            {"│", "|"},
            {"•", "-"},
            {"●", "*"},
            {"⚙", "*"},
            {"✓", "+"},
            {"✗", "x"},
            {"⠋", "-"}, {"⠙", "-"}, {"⠹", "\\"}, {"⠸", "\\"}, {"⠼", "|"},
            {"⠴", "|"}, {"⠦", "/"}, {"⠧", "/"}, {"⠇", "-"}, {"⠏", "-"},
    };

    private final boolean supported;

    StatusGlyphs(Charset encoding) {
        this.supported = encodes(encoding);
    }

    private static boolean encodes(Charset encoding) {
        if (encoding == null) return false;
        try {
            CharsetEncoder encoder = encoding.newEncoder();
            for (String[] substitution : SUBSTITUTIONS) {
                if (!encoder.canEncode(substitution[0])) return false;
            }
            return true;
        } catch (UnsupportedOperationException e) {
            // A charset that cannot encode at all certainly cannot carry these.
            return false;
        }
    }

    /** @return the line unchanged where the console can carry it, ASCII otherwise */
    String downgrade(String line) {
        if (supported || line == null || line.isEmpty()) return line;
        String result = line;
        for (String[] substitution : SUBSTITUTIONS) {
            if (result.indexOf(substitution[0].charAt(0)) >= 0) {
                result = result.replace(substitution[0], substitution[1]);
            }
        }
        return result;
    }

    boolean supported() {
        return supported;
    }
}
