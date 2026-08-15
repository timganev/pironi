package dev.pironi.agent;

import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;
import dev.pironi.status.ThemeSettings;

import java.io.PrintStream;
import java.util.function.Consumer;
import java.util.concurrent.locks.LockSupport;

/** Incrementally renders an already validated final answer. */
public final class FinalAnswerStreamer implements Consumer<String> {
    private static final long MAX_TOTAL_DELAY_MILLIS = 1_800;
    private static final long MIN_CHUNK_DELAY_MILLIS = 8;
    private final PrintStream output;
    private final Terminal terminal;
    private final Consumer<Long> pauseMillis;
    private final ThemeSettings theme;

    public FinalAnswerStreamer(PrintStream output, Terminal terminal) {
        this(output, terminal, new ThemeSettings());
    }

    public FinalAnswerStreamer(PrintStream output, Terminal terminal, ThemeSettings theme) {
        this(output, terminal, millis -> LockSupport.parkNanos(millis * 1_000_000), theme);
    }

    FinalAnswerStreamer(PrintStream output, Terminal terminal, Consumer<Long> pauseMillis) {
        this(output, terminal, pauseMillis, new ThemeSettings());
    }

    FinalAnswerStreamer(PrintStream output, Terminal terminal, Consumer<Long> pauseMillis,
            ThemeSettings theme) {
        this.output = output;
        this.terminal = terminal;
        this.pauseMillis = pauseMillis;
        this.theme = theme;
    }

    @Override public void accept(String value) {
        if (value == null || value.isEmpty()) return;
        if (value.equals(System.lineSeparator()) || value.equals("\n") || value.equals("\r\n")) {
            write(value);
            return;
        }
        String[] chunks = value.split("(?<=\\s)|(?=\\s)");
        long delay = Math.max(MIN_CHUNK_DELAY_MILLIS,
                Math.min(35, MAX_TOTAL_DELAY_MILLIS / Math.max(1, chunks.length)));
        for (int index = 0; index < chunks.length; index++) {
            write(chunks[index]);
            if (index + 1 < chunks.length) pauseMillis.accept(delay);
        }
    }

    private void write(String chunk) {
        if (terminal != null) {
            synchronized (terminal) {
                terminal.writer().print(new AttributedString(
                        crlf(chunk), theme.style(ThemeSettings.Element.AGENT)
                ).toAnsi(terminal));
                terminal.flush();
            }
        } else {
            output.print(chunk);
            output.flush();
        }
    }

    /**
     * A terminal in raw mode treats a bare LF as "down one row" without returning to column one,
     * so each line of a multi-line answer starts where the previous one ended and the text walks
     * diagonally down the screen. Only the terminal path needs this; a plain PrintStream handles
     * newlines itself.
     */
    static String crlf(String chunk) {
        return chunk.indexOf('\n') < 0 ? chunk : chunk.replace("\r\n", "\n").replace("\n", "\r\n");
    }
}
