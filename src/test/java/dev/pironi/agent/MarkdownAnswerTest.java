package dev.pironi.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownAnswerTest {

    private static String[] linesOf(String rendered) {
        return rendered.split(System.lineSeparator(), -1);
    }

    @Test
    void aTableIsPaddedSoItsColumnsLineUp() {
        String answer = String.join("\n",
                "| Град | Събота |",
                "|---|---|",
                "| София | превалявания |",
                "| Варна | ясно |");

        String[] lines = linesOf(MarkdownAnswer.render(answer, 0));

        // Lining up means the second column starts at the same screen column on every row.
        // Row width itself differs, because trailing padding is stripped rather than printed.
        assertEquals(4, lines.length);
        int header = lines[0].indexOf("Събота");
        assertEquals(header, lines[2].indexOf("превалявания"), lines[2]);
        assertEquals(header, lines[3].indexOf("ясно"), lines[3]);
        assertTrue(lines[2].startsWith("София"), lines[2]);
    }

    @Test
    void cyrillicCellsAreMeasuredAsOneColumnEach() {
        assertEquals(5, MarkdownAnswer.displayWidth("София"));
        assertEquals(2, MarkdownAnswer.displayWidth("🔩"));
    }

    @Test
    void emphasisMarkersAreDroppedRatherThanPrinted() {
        assertEquals("София е гореща", MarkdownAnswer.stripEmphasis("**София** е гореща"));
        assertEquals("нищо", MarkdownAnswer.stripEmphasis("нищо"));
        // An unpaired marker is left alone: it may be multiplication, not emphasis.
        assertEquals("2 ** 8", MarkdownAnswer.stripEmphasis("2 ** 8"));
    }

    @Test
    void aFencedBlockKeepsItsOwnSpacing() {
        String answer = String.join("\n", "before", "```", "  indented  **kept**", "```", "after");

        String rendered = MarkdownAnswer.render(answer, 20);

        assertTrue(rendered.contains("  indented  **kept**"), rendered);
    }

    @Test
    void proseIsWrappedAtTheWindowWidth() {
        String answer = "one two three four five six seven eight nine ten eleven twelve";

        String[] lines = linesOf(MarkdownAnswer.render(answer, 20));

        assertTrue(lines.length > 1, "expected wrapping");
        for (String line : lines) {
            assertTrue(MarkdownAnswer.displayWidth(line) <= 20, line);
        }
    }

    @Test
    void aWidthOfZeroLeavesProseUntouched() {
        String answer = "one two three four five six seven eight nine ten eleven twelve";

        assertEquals(answer, MarkdownAnswer.render(answer, 0));
    }

    @Test
    void textThatIsNotATableSurvivesUnchanged() {
        String answer = String.join("\n", "first line", "", "second line");

        assertEquals(String.join(System.lineSeparator(), "first line", "", "second line"),
                MarkdownAnswer.render(answer, 0));
    }

    @Test
    void aPipeWithoutADelimiterRowIsNotTreatedAsATable() {
        String answer = String.join("\n", "| not a table", "| still not one");

        String[] lines = linesOf(MarkdownAnswer.render(answer, 0));

        assertEquals("| not a table", lines[0]);
        assertEquals("| still not one", lines[1]);
    }

    @Test
    void rightAlignedColumnsArePaddedOnTheLeft() {
        String answer = String.join("\n",
                "| item | count |",
                "|---|---:|",
                "| a | 5 |",
                "| bbbb | 1000 |");

        String[] lines = linesOf(MarkdownAnswer.render(answer, 0));

        assertTrue(lines[2].endsWith("   5"), lines[2]);
        assertTrue(lines[3].endsWith("1000"), lines[3]);
    }
}
