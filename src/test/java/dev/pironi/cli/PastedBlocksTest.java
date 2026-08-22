package dev.pironi.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PastedBlocksTest {
    @Test
    void aPastedBlockStandsAsOneLineAndComesBackWhole() {
        PastedBlocks pastes = new PastedBlocks();
        String block = String.join("\n", "one", "two", "three", "four");

        String shown = pastes.collapse(block);

        assertEquals("[Pasted text 4 lines]", shown);
        assertEquals("look at " + block + " please",
                pastes.expand("look at " + shown + " please"));
    }

    @Test
    void oneLineIsLeftAlone() {
        PastedBlocks pastes = new PastedBlocks();

        // Standing in for a line the person can already see costs them a keystroke to find out
        // what it was.
        assertEquals("just a url", pastes.collapse("just a url"));
        assertEquals("", pastes.collapse(""));
        assertTrue(pastes.isEmpty());
    }

    @Test
    void twoBlocksComeBackInTheOrderTheyWentIn() {
        PastedBlocks pastes = new PastedBlocks();
        String first = "a\nb";
        String second = "c\nd\ne";

        String one = pastes.collapse(first);
        String two = pastes.collapse(second);

        assertEquals("[Pasted text 2 lines]", one);
        assertEquals("[Pasted text 3 lines]", two);
        assertEquals("compare " + first + " with " + second,
                pastes.expand("compare " + one + " with " + two));
        assertTrue(pastes.isEmpty(), "each block is owed once");
    }

    @Test
    void wordsThatMerelyLookLikeAPlaceholderAreNotSubstituted() {
        PastedBlocks pastes = new PastedBlocks();
        pastes.collapse("a\nb");

        // Someone typing about the feature, or editing the count we wrote, must not have some
        // other paste dropped in its place.
        String typed = "the prompt says [Pasted text 9 lines] and I did not paste that";
        assertEquals(typed, pastes.expand(typed));
        assertTrue(!pastes.isEmpty(), "the real block is still owed");
    }

    @Test
    void nothingIsCarriedFromOneLineToTheNext() {
        PastedBlocks pastes = new PastedBlocks();
        pastes.collapse("a\nb");

        pastes.clear();

        assertEquals("[Pasted text 2 lines]", pastes.expand("[Pasted text 2 lines]"));
    }

    @Test
    void linesAreCountedAsAPersonCountsThem() {
        assertEquals(1, PastedBlocks.lineCount("one"));
        assertEquals(2, PastedBlocks.lineCount("one\ntwo"));
        assertEquals(2, PastedBlocks.lineCount("one\ntwo\n"), "a trailing newline opens nothing");
        assertEquals(3, PastedBlocks.lineCount("one\r\ntwo\r\nthree"), "CRLF is one break");
        assertEquals(2, PastedBlocks.lineCount("one\rtwo"), "so is a bare CR");
        assertEquals(0, PastedBlocks.lineCount(""));
        assertEquals(0, PastedBlocks.lineCount(null));
    }

    @Test
    void whatThePasteAddedIsTakenFromWhereTheCursorWas() {
        assertEquals("pasted", InteractiveShell.insertedText("ab", "apastedb", 1));
        assertEquals("x", InteractiveShell.insertedText("", "x", 0));
        // A buffer that did not grow, or a cursor past its end, describes no paste at all.
        assertEquals("", InteractiveShell.insertedText("ab", "ab", 1));
        assertEquals("", InteractiveShell.insertedText("ab", "a", 1));
        assertEquals("", InteractiveShell.insertedText("ab", "abcd", 9));
    }
}
