package dev.pironi.status;

import java.util.Locale;

/**
 * JLine draws box characters through the terminfo alternate character set rather than as text: it
 * emits the DEC Special Graphics shift and then the byte standing for the glyph, so U+2502 leaves
 * the process as {@code ESC(0} {@code x} {@code ESC(B}. A terminal that ignores the shift prints
 * the byte, and every separator in the status row reads as a bare "x".
 *
 * <p>The Windows console ignores it, and TERM=xterm-256color is what hands JLine the capabilities
 * that switch the translation on. Nothing on our side can see this happen: the console encoding
 * carries U+2502 perfectly well, so {@link StatusGlyphs} keeps the character and the substitution
 * happens downstream of it. What identified the culprit was that only the line-drawing character
 * broke - the bullet, the gear and the marks on the same row all survived.
 */
public final class AlternateCharset {
    /** JLine reads this once, in a static initialiser, the first time it renders anything. */
    static final String PROPERTY = "org.jline.utils.disableAlternateCharset";

    private AlternateCharset() {
    }

    /** Must run before JLine loads, or the property is read before it is set. */
    public static void disableWhereUnsupported() {
        if (shouldDisable(System.getProperty("os.name", ""), System.getProperty(PROPERTY))) {
            System.setProperty(PROPERTY, "true");
        }
    }

    /**
     * @param alreadySet what the property holds, or null when nobody has set it
     * @return true where the translation would be emitted into a console that drops it
     */
    static boolean shouldDisable(String osName, String alreadySet) {
        // Someone who set it either way has decided; a terminal we have not seen may need the
        // translation, and on Linux without UTF-8 it is the only thing that draws the row at all.
        if (alreadySet != null) return false;
        return osName != null && osName.toLowerCase(Locale.ROOT).contains("win");
    }
}
