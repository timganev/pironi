package dev.pironi.cli;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A terminal that brackets a paste says so, and the block never reaches the prompt as separate
 * lines. Windows does not: JLine's native terminal hands a paste over as ordinary characters, so
 * every newline in it is an Enter, and eighteen pasted lines became eighteen turns, each answered
 * on its own. Verified with a probe: no ESC[200~ ever arrives there.
 *
 * <p>What is left to go on is that the following line is already waiting. This is that rule, apart
 * from the terminal it has to run against.
 */
class PastedRemainderTest {
    /** A paste: every line is already there when the first is accepted. */
    private static InteractiveShell.Block joinAll(String first, String... rest) {
        Deque<String> waiting = new ArrayDeque<>(List.of(rest));
        return InteractiveShell.joinArrivedTogether(first, () -> !waiting.isEmpty(),
                waiting::pollFirst, InteractiveShell.MAX_PASTED_LINES);
    }

    @Test
    void linesThatArrivedTogetherBecomeOne() {
        InteractiveShell.Block block = joinAll("one", "two", "three");

        assertEquals(3, block.lines());
        assertEquals(String.join(System.lineSeparator(), "one", "two", "three"), block.text());
    }

    @Test
    void aTypedLineIsLeftExactlyAsItWas() {
        // Nothing waiting: a person pressed Enter, and joining anything to that would be inventing
        // input they did not give.
        InteractiveShell.Block block = InteractiveShell.joinArrivedTogether(
                "just this", () -> false, () -> "should never be read", 500);

        assertEquals(1, block.lines());
        assertEquals("just this", block.text());
    }

    @Test
    void theCeilingHolds() {
        Deque<String> waiting = new ArrayDeque<>();
        for (int i = 0; i < 50; i++) waiting.add("line " + i);

        InteractiveShell.Block block = InteractiveShell.joinArrivedTogether(
                "first", () -> !waiting.isEmpty(), waiting::pollFirst, 4);

        assertEquals(4, block.lines(), "a program piping at us cannot make one unbounded line");
        assertEquals(47, waiting.size(), "the rest is left for the next read, not swallowed");
    }

    @Test
    void inputEndingMidBlockStopsThere() {
        // The stream can close, or the reader can be interrupted, between two lines of a paste.
        Deque<String> waiting = new ArrayDeque<>(List.of("two"));
        InteractiveShell.Block block = InteractiveShell.joinArrivedTogether(
                "one", () -> true, waiting::pollFirst, 500);

        assertEquals(2, block.lines());
        assertEquals("one" + System.lineSeparator() + "two", block.text());
    }

    @Test
    void endOfInputIsPassedOnRatherThanTurnedIntoText() {
        InteractiveShell.Block block = InteractiveShell.joinArrivedTogether(
                null, () -> true, () -> "ignored", 500);

        assertEquals(null, block.text(), "null means the session is over and must stay null");
        assertEquals(0, block.lines());
    }

    @Test
    void aCeilingBelowTwoJoinsNothing() {
        InteractiveShell.Block block = InteractiveShell.joinArrivedTogether(
                "one", () -> true, () -> "two", 1);

        assertEquals(1, block.lines());
        assertEquals("one", block.text());
    }

    @Test
    void whatIsShownNamesTheWholeBlock() {
        assertEquals("[Pasted text 3 lines]",
                PastedBlocks.placeholder(joinAll("one", "two", "three").lines()));
    }
}
