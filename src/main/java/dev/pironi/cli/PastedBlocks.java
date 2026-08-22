package dev.pironi.cli;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps a pasted block out of the prompt and hands it back when the line is submitted.
 *
 * <p>Eighteen pasted lines scroll the prompt off the screen and bury whatever the person meant to
 * type around them. The line stands in for the block while it is being edited; the agent still
 * receives every character of it.
 */
final class PastedBlocks {
    /** Below this a block is easier read than referred to. */
    static final int MIN_COLLAPSED_LINES = 2;

    private static final Pattern PLACEHOLDER = Pattern.compile("\\[Pasted text (\\d+) lines]");

    private final Deque<String> blocks = new ArrayDeque<>();

    /** @return the text to show in the prompt, which is the text itself when it is a single line */
    String collapse(String pasted) {
        if (pasted == null || pasted.isEmpty()) return "";
        int lines = lineCount(pasted);
        if (lines < MIN_COLLAPSED_LINES) return pasted;
        blocks.addLast(pasted);
        return placeholder(lines);
    }

    /**
     * Puts the blocks back, in the order they were pasted.
     *
     * <p>A placeholder whose line count does not match the block waiting for it is left alone: the
     * person either typed those words themselves or edited the one we wrote, and inserting some
     * other paste there would be worse than doing nothing.
     */
    String expand(String line) {
        if (line == null || line.isEmpty() || blocks.isEmpty()) return line;
        Matcher matcher = PLACEHOLDER.matcher(line);
        StringBuilder expanded = new StringBuilder();
        while (matcher.find()) {
            String block = blocks.peekFirst();
            if (block != null && lineCount(block) == Integer.parseInt(matcher.group(1))) {
                blocks.removeFirst();
                matcher.appendReplacement(expanded, Matcher.quoteReplacement(block));
            } else {
                matcher.appendReplacement(expanded, Matcher.quoteReplacement(matcher.group()));
            }
        }
        matcher.appendTail(expanded);
        return expanded.toString();
    }

    /** Between lines nothing is carried over; a block nobody submitted is not owed to anyone. */
    void clear() {
        blocks.clear();
    }

    boolean isEmpty() {
        return blocks.isEmpty();
    }

    static String placeholder(int lines) {
        return "[Pasted text " + lines + " lines]";
    }

    /** Lines as a person counts them: a trailing newline does not open another one. */
    static int lineCount(String text) {
        if (text == null || text.isEmpty()) return 0;
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') continue;
            if (c == '\n' || c == '\r') lines++;
        }
        if (text.endsWith("\n") || text.endsWith("\r")) lines--;
        return Math.max(lines, 1);
    }
}
