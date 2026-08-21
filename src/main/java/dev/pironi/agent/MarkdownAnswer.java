package dev.pironi.agent;

import java.util.ArrayList;
import java.util.List;

/**
 * Lays out a final answer for a terminal.
 *
 * <p>The harness used to print the model's markdown verbatim, so a table arrived as its source:
 * cells of unequal width between pipes, with the widest row running past the window and wrapping
 * into the next one. Nothing here interprets the answer - columns are padded, emphasis markers
 * are dropped, and prose is wrapped at the window width.
 */
public final class MarkdownAnswer {
    /** Used when the window width is unknown, so a table still gets padded. */
    private static final int NO_WRAP = 0;

    private MarkdownAnswer() {
    }

    public static String render(String answer, int width) {
        if (answer == null || answer.isEmpty()) return answer;
        String normalized = answer.replace("\r\n", "\n");
        List<String> lines = List.of(normalized.split("\n", -1));
        List<String> out = new ArrayList<>();
        boolean inFence = false;
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.strip().startsWith("```")) {
                inFence = !inFence;
                out.add(line);
                continue;
            }
            if (inFence) {
                // A fenced block is code: its spacing is the content, not decoration.
                out.add(line);
                continue;
            }
            int tableEnd = tableEndingAt(lines, index);
            if (tableEnd > index) {
                out.addAll(renderTable(lines.subList(index, tableEnd)));
                index = tableEnd - 1;
                continue;
            }
            out.addAll(wrap(stripEmphasis(line), width));
        }
        return String.join(System.lineSeparator(), out);
    }

    /**
     * @return the index one past the last row of the table starting here, or {@code start} when
     *     these lines are not a table
     */
    private static int tableEndingAt(List<String> lines, int start) {
        if (start + 1 >= lines.size()) return start;
        if (!isRow(lines.get(start)) || !isDelimiter(lines.get(start + 1))) return start;
        int end = start + 2;
        while (end < lines.size() && isRow(lines.get(end))) end++;
        return end;
    }

    private static boolean isRow(String line) {
        String stripped = line.strip();
        return stripped.startsWith("|") && stripped.length() > 1;
    }

    private static boolean isDelimiter(String line) {
        if (!isRow(line)) return false;
        for (String cell : cells(line)) {
            if (!cell.strip().matches(":?-{1,}:?")) return false;
        }
        return true;
    }

    private static List<String> cells(String line) {
        String stripped = line.strip();
        if (stripped.startsWith("|")) stripped = stripped.substring(1);
        if (stripped.endsWith("|")) stripped = stripped.substring(0, stripped.length() - 1);
        List<String> cells = new ArrayList<>();
        for (String cell : stripped.split("\\|", -1)) cells.add(stripEmphasis(cell.strip()));
        return cells;
    }

    private static List<String> renderTable(List<String> rows) {
        List<List<String>> body = new ArrayList<>();
        List<String> alignments = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            if (index == 1) {
                for (String cell : cells(rows.get(index))) alignments.add(alignmentOf(cell));
                continue;
            }
            body.add(cells(rows.get(index)));
        }
        int columns = 0;
        for (List<String> row : body) columns = Math.max(columns, row.size());
        int[] widths = new int[columns];
        for (List<String> row : body) {
            for (int column = 0; column < row.size(); column++) {
                widths[column] = Math.max(widths[column], displayWidth(row.get(column)));
            }
        }
        List<String> out = new ArrayList<>();
        for (int index = 0; index < body.size(); index++) {
            out.add(renderRow(body.get(index), widths, alignments));
            if (index == 0) out.add(renderSeparator(widths, alignments));
        }
        return out;
    }

    private static String alignmentOf(String delimiter) {
        boolean left = delimiter.startsWith(":");
        boolean right = delimiter.endsWith(":");
        if (left && right) return "center";
        return right ? "right" : "left";
    }

    private static String renderRow(List<String> row, int[] widths, List<String> alignments) {
        StringBuilder line = new StringBuilder();
        for (int column = 0; column < widths.length; column++) {
            String cell = column < row.size() ? row.get(column) : "";
            String alignment = column < alignments.size() ? alignments.get(column) : "left";
            line.append(column == 0 ? "" : "  ").append(pad(cell, widths[column], alignment));
        }
        return line.toString().stripTrailing();
    }

    private static String renderSeparator(int[] widths, List<String> alignments) {
        StringBuilder line = new StringBuilder();
        for (int column = 0; column < widths.length; column++) {
            line.append(column == 0 ? "" : "  ").append("-".repeat(widths[column]));
        }
        return line.toString();
    }

    private static String pad(String cell, int width, String alignment) {
        int missing = width - displayWidth(cell);
        if (missing <= 0) return cell;
        return switch (alignment) {
            case "right" -> " ".repeat(missing) + cell;
            case "center" -> " ".repeat(missing / 2) + cell + " ".repeat(missing - missing / 2);
            default -> cell + " ".repeat(missing);
        };
    }

    /** Drops emphasis markers, which a terminal shows as the asterisks they are. */
    static String stripEmphasis(String text) {
        String result = text;
        while (true) {
            int open = result.indexOf("**");
            if (open < 0) break;
            int close = result.indexOf("**", open + 2);
            if (close < 0) break;
            result = result.substring(0, open) + result.substring(open + 2, close)
                    + result.substring(close + 2);
        }
        return result;
    }

    private static List<String> wrap(String line, int width) {
        if (width <= NO_WRAP || displayWidth(line) <= width) return List.of(line);
        String indent = line.substring(0, line.length() - line.stripLeading().length());
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder(indent);
        boolean empty = true;
        for (String word : line.strip().split(" ")) {
            int candidate = displayWidth(current.toString()) + (empty ? 0 : 1) + displayWidth(word);
            if (!empty && candidate > width) {
                out.add(current.toString());
                current = new StringBuilder(indent);
                empty = true;
            }
            if (!empty) current.append(' ');
            current.append(word);
            empty = false;
        }
        out.add(current.toString());
        return out;
    }

    /** Columns on screen, not chars: one emoji or CJK ideograph occupies two cells. */
    static int displayWidth(String text) {
        int width = 0;
        for (int index = 0; index < text.length(); ) {
            int codePoint = text.codePointAt(index);
            index += Character.charCount(codePoint);
            width += isWide(codePoint) ? 2 : 1;
        }
        return width;
    }

    private static boolean isWide(int codePoint) {
        return (codePoint >= 0x1100 && codePoint <= 0x115F)
                || (codePoint >= 0x2E80 && codePoint <= 0xA4CF)
                || (codePoint >= 0xAC00 && codePoint <= 0xD7A3)
                || (codePoint >= 0xF900 && codePoint <= 0xFAFF)
                || (codePoint >= 0xFE30 && codePoint <= 0xFE6F)
                || (codePoint >= 0xFF00 && codePoint <= 0xFF60)
                || (codePoint >= 0xFFE0 && codePoint <= 0xFFE6)
                || (codePoint >= 0x1F300 && codePoint <= 0x1FAFF);
    }
}
