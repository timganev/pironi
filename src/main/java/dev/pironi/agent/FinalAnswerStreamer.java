package dev.pironi.agent;

import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;

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

    public FinalAnswerStreamer(PrintStream output, Terminal terminal) {
        this(output, terminal, millis -> LockSupport.parkNanos(millis * 1_000_000));
    }

    FinalAnswerStreamer(PrintStream output, Terminal terminal, Consumer<Long> pauseMillis) {
        this.output = output;
        this.terminal = terminal;
        this.pauseMillis = pauseMillis;
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
                        chunk, AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN)
                ).toAnsi(terminal));
                terminal.flush();
            }
        } else {
            output.print(chunk);
            output.flush();
        }
    }
}
