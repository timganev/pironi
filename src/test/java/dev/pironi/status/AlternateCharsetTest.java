package dev.pironi.status;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlternateCharsetTest {

    @Test
    void turnsTheTranslationOffOnWindows() {
        assertTrue(AlternateCharset.shouldDisable("Windows 11", null));
        assertTrue(AlternateCharset.shouldDisable("Windows Server 2022", null));
    }

    @Test
    void leavesOtherConsolesAlone() {
        assertFalse(AlternateCharset.shouldDisable("Linux", null));
        assertFalse(AlternateCharset.shouldDisable("Mac OS X", null));
    }

    @Test
    void anExplicitSettingOutranksOurs() {
        assertFalse(AlternateCharset.shouldDisable("Windows 11", "false"));
        assertFalse(AlternateCharset.shouldDisable("Windows 11", "true"));
    }

    @Test
    void anUnknownSystemIsNotGuessedAt() {
        assertFalse(AlternateCharset.shouldDisable(null, null));
        assertFalse(AlternateCharset.shouldDisable("", null));
    }

    @Test
    void jLineStillReadsTheSwitchThisPropertyFlips() {
        // The workaround is a property name, so a JLine upgrade that renames or drops the switch
        // would leave it doing nothing at all and no other test would notice.
        assertDoesNotThrow(() -> Class.forName("org.jline.utils.AttributedCharSequence")
                .getDeclaredField("DISABLE_ALTERNATE_CHARSET"));
    }
}
