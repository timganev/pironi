package dev.pironi.status;

import dev.pironi.model.ChatMessage;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jline.utils.Status;

import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TerminalStatusReporter implements StatusReporter {
    private static final String[] SPINNER = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};
    private static final String CLEAR_SCREEN = "\u001B[2J";
    private static final String CURSOR_HOME = "\u001B[H";

    private volatile String model;
    private final String workspace;
    private volatile int contextSize;
    private final int maxTurns;
    private final PrintStream output;
    private final Terminal terminal;
    private final Status terminalStatus;
    private final Object outputLock = new Object();
    private volatile int lastContextPercent;
    private boolean closed;

    public TerminalStatusReporter(
            String model,
            Path workspace,
            int contextSize,
            int maxTurns,
            PrintStream output
    ) {
        this(model, workspace, contextSize, maxTurns, output, null);
    }

    public TerminalStatusReporter(
            String model,
            Path workspace,
            int contextSize,
            int maxTurns,
            PrintStream output,
            Terminal terminal
    ) {
        this.model = model;
        this.workspace = workspace.getFileName() == null
                ? workspace.toString()
                : workspace.getFileName().toString();
        this.contextSize = contextSize;
        this.maxTurns = maxTurns;
        this.output = output;
        this.terminal = terminal;
        this.terminalStatus = createStatus(terminal);
    }

    @Override
    public Activity thinking(int turn, List<ChatMessage> messages) {
        int contextPercent = estimateContextPercent(messages, contextSize);
        lastContextPercent = contextPercent;
        AtomicBoolean running = new AtomicBoolean(true);
        long started = System.nanoTime();
        Thread ticker = Thread.startVirtualThread(() -> {
            int frame = 0;
            while (running.get()) {
                long seconds = Duration.ofNanos(System.nanoTime() - started).toSeconds();
                render(formatLine(
                        SPINNER[frame++ % SPINNER.length],
                        model,
                        workspace,
                        contextPercent,
                        seconds,
                        turn,
                        maxTurns
                ));
                try {
                    Thread.sleep(1_000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });

        return () -> {
            running.set(false);
            ticker.interrupt();
            try {
                ticker.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            render("● " + model + " │ " + workspace + " │ ctx ~"
                    + formatContextPercent(lastContextPercent) + " │ processing");
        };
    }

    @Override
    public void tool(String toolName) {
        render("⚙ " + model + " │ " + workspace + " │ ctx ~"
                + formatContextPercent(lastContextPercent) + " │ tool " + toolName);
    }

    @Override
    public void idle() {
        render("● " + model + " │ " + workspace + " │ ctx ~"
                + formatContextPercent(lastContextPercent) + " │ ready");
    }

    @Override
    public void configurationChanged(String model, int contextSize) {
        this.model = model;
        this.contextSize = contextSize;
        lastContextPercent = 0;
        idle();
    }

    static int estimateContextPercent(List<ChatMessage> messages, int contextSize) {
        long characters = messages.stream()
                .mapToLong(message -> message.role().length() + message.content().length())
                .sum();
        long estimatedTokens = Math.max(1, (characters + 3) / 4);
        return (int) Math.min(100, Math.round(estimatedTokens * 100.0 / contextSize));
    }

    static String formatLine(
            String spinner,
            String model,
            String workspace,
            int contextPercent,
            long seconds,
            int turn,
            int maxTurns
    ) {
        return "%s %s │ %s │ ctx ~%s │ working %ds │ turn %d/%d".formatted(
                spinner,
                model,
                workspace,
                formatContextPercent(contextPercent),
                seconds,
                turn,
                maxTurns
        );
    }

    static String formatContextPercent(int contextPercent) {
        return contextPercent == 0 ? "<1%" : contextPercent + "%";
    }

    private void render(String line) {
        if (useJLine()) {
            renderViaJLine(line);
        } else if (terminal != null) {
            renderViaDumbTerminal(line);
        } else {
            renderViaRaw(line);
        }
    }

    private boolean useJLine() {
        if (terminal == null) return false;
        return !"dumb".equals(terminal.getType())
                && terminal.getSize().getRows() > 0;
    }

    private void renderViaJLine(String line) {
        synchronized (terminal) {
            if (closed) return;
            terminalStatus.resize();
            terminalStatus.update(List.of(styledStatus(line)));
        }
    }

    private void renderViaRaw(String line) {
        synchronized (outputLock) {
            if (closed) return;
            output.print("\r\u001B[2K" + line);
            output.flush();
        }
    }

    private void renderViaDumbTerminal(String line) {
        synchronized (outputLock) {
            if (closed) return;
            output.println(line);
            output.flush();
        }
    }

    @Override
    public void close() {
        if (useJLine()) {
            synchronized (terminal) {
                if (closed) return;
                closed = true;
                terminalStatus.close();
                terminal.writer().print(CLEAR_SCREEN + CURSOR_HOME);
                terminal.flush();
            }
        } else {
            synchronized (outputLock) {
                if (closed) return;
                closed = true;
                output.print("\u001B[r" + CLEAR_SCREEN + CURSOR_HOME);
                output.flush();
            }
        }
    }

    private static Status createStatus(Terminal terminal) {
        if (terminal == null || "dumb".equals(terminal.getType())) {
            return null;
        }
        synchronized (terminal) {
            terminal.writer().print(CLEAR_SCREEN + CURSOR_HOME);
            terminal.flush();
            return Status.getStatus(terminal);
        }
    }

    private static AttributedString styledStatus(String line) {
        int modelEnd = line.indexOf(" │ ");
        if (modelEnd < 0) {
            return new AttributedString(line);
        }
        return new AttributedStringBuilder()
                .style(AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW).bold())
                .append(line, 0, modelEnd)
                .style(AttributedStyle.DEFAULT.foreground(AttributedStyle.WHITE))
                .append(line.substring(modelEnd))
                .toAttributedString();
    }
}
