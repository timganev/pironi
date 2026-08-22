package dev.pironi.status;

import dev.pironi.model.ChatMessage;
import dev.pironi.model.ModelResponse;
import com.fasterxml.jackson.databind.JsonNode;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jline.utils.Status;
import org.jline.utils.InfoCmp;

import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TerminalStatusReporter implements StatusReporter {
    private static final String[] SPINNER = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};
    private static final String CLEAR_SCREEN = "\u001B[2J";
    private static final String CURSOR_HOME = "\u001B[H";

    private volatile String model;
    private volatile String workspace;
    private volatile int contextSize;
    private final int maxTurns;
    private final PrintStream output;
    private final Terminal terminal;
    private final Status terminalStatus;
    private final ThemeSettings theme;
    private final StatusGlyphs glyphs;
    private final Object outputLock = new Object();
    private volatile int lastContextPercent;
    private volatile double lastEvalTokensPerSecond;
    private volatile boolean lastEvalRateApproximate;
    private volatile java.util.function.Supplier<int[]> subagentCounts;
    /**
     * How many writers are putting text on the screen right now. A counter rather than a flag: the
     * approval prompt nests inside a run, and a flag would let the inner one clear the outer.
     */
    private final java.util.concurrent.atomic.AtomicInteger outputInProgress =
            new java.util.concurrent.atomic.AtomicInteger();
    private volatile String heldLine;
    /**
     * Activity lines that arrived while something was printing. Dropping them would lose what a
     * sub-agent did; they are held and printed once the screen is free.
     */
    private final java.util.Queue<String> heldActivity =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    private boolean closed;

    /** Sets a supplier returning {active, max} sub-agents, rendered as "sub A/M". Null disables. */
    public void setSubagentCounts(java.util.function.Supplier<int[]> subagentCounts) {
        this.subagentCounts = subagentCounts;
    }

    public TerminalStatusReporter(
            String model,
            Path workspace,
            int contextSize,
            int maxTurns,
            PrintStream output
    ) {
        this(model, workspace, contextSize, maxTurns, output, null, new ThemeSettings());
    }

    public TerminalStatusReporter(
            String model, Path workspace, int contextSize, int maxTurns,
            PrintStream output, ThemeSettings theme
    ) {
        this(model, workspace, contextSize, maxTurns, output, null, theme);
    }

    public TerminalStatusReporter(
            String model,
            Path workspace,
            int contextSize,
            int maxTurns,
            PrintStream output,
            Terminal terminal
    ) {
        this(model, workspace, contextSize, maxTurns, output, terminal, new ThemeSettings());
    }

    public TerminalStatusReporter(
            String model, Path workspace, int contextSize, int maxTurns,
            PrintStream output, Terminal terminal, ThemeSettings theme
    ) {
        this.model = model;
        this.workspace = label(workspace);
        this.contextSize = contextSize;
        this.maxTurns = maxTurns;
        this.output = output;
        this.terminal = terminal;
        this.theme = theme;
        this.terminalStatus = createStatus(terminal);
        this.glyphs = new StatusGlyphs(consoleEncoding(terminal, output));
    }

    /** What the row is actually written through, which is JLine's when there is a terminal. */
    private static java.nio.charset.Charset consoleEncoding(Terminal terminal, PrintStream output) {
        try {
            if (terminal != null) return terminal.encoding();
        } catch (RuntimeException ignored) {
            // An unusual terminal may not report one; the stream below still does.
        }
        return output == null ? java.nio.charset.Charset.defaultCharset() : output.charset();
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
                render(withEvalRate(formatLine(
                        SPINNER[frame++ % SPINNER.length],
                        model,
                        workspace,
                        contextPercent,
                        seconds,
                        turn,
                        maxTurns
                )));
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
            render(withEvalRate("● " + model + " │ " + workspace + " │ ctx ~"
                    + formatContextPercent(lastContextPercent) + " │ processing"));
        };
    }

    @Override
    public void tool(String toolName) {
        render(withEvalRate("⚙ " + model + " │ " + workspace + " │ ctx ~"
                + formatContextPercent(lastContextPercent) + " │ tool " + toolName));
    }

    @Override
    public void skill(String skillName) {
        activityLine("• Using skill " + activityValue(skillName));
    }

    @Override
    public void toolStarted(String toolName, JsonNode arguments) {
        activityLine("• " + ToolActivityFormatter.started(toolName, arguments));
        tool(toolName);
    }

    @Override
    public void toolFinished(String toolName, boolean success, long durationMillis) {
        toolFinished(toolName, success, durationMillis, "");
    }

    @Override
    public void toolFinished(String toolName, boolean success, long durationMillis, String why) {
        activityLine((success ? "✓ " : "✗ ")
                + ToolActivityFormatter.finished(toolName, success, durationMillis)
                + (success ? "" : firstLine(why)));
    }

    /** One line is enough to act on, and a tool that failed may have printed a great many. */
    private static String firstLine(String why) {
        if (why == null || why.isBlank()) return "";
        String line = why.strip().lines().findFirst().orElse("").strip();
        if (line.isEmpty()) return "";
        if (line.length() > 160) line = line.substring(0, 160) + "…";
        return " — " + line;
    }

    @Override
    public void idle() {
        render(withEvalRate("● " + model + " │ " + workspace + " │ ctx ~"
                + formatContextPercent(lastContextPercent) + " │ ready"));
    }

    @Override
    public void modelResponse(ModelResponse response) {
        if (response.outputTokens() > 0 && response.evalDurationNanos() > 0) {
            lastEvalTokensPerSecond = response.outputTokens() * 1_000_000_000.0
                    / response.evalDurationNanos();
            lastEvalRateApproximate = false;
        } else if (response.outputTokens() > 0 && response.durationNanos() > 0) {
            lastEvalTokensPerSecond = response.outputTokens() * 1_000_000_000.0
                    / response.durationNanos();
            lastEvalRateApproximate = true;
        }
    }

    @Override
    public void outputStarted() {
        outputInProgress.incrementAndGet();
        if (useJLine()) {
            synchronized (terminal) {
                if (closed) return;
                terminalStatus.suspend();
                terminal.flush();
            }
            return;
        }
        // The status sits on the current row with no trailing newline so it can be redrawn in
        // place.
        eraseRawStatusRow();
    }

    @Override
    public void outputFinished() {
        boolean last = outputInProgress.decrementAndGet() <= 0;
        if (useJLine()) {
            synchronized (terminal) {
                if (closed) return;
                terminalStatus.restore();
                terminal.flush();
            }
        }
        // Nothing to restore on the raw path: the next refresh redraws the row.
        if (!last) return;
        for (String activity = heldActivity.poll(); activity != null; activity = heldActivity.poll()) {
            activityLine(activity);
        }
        String held = heldLine;
        heldLine = null;
        // Whatever the row would have said during the output, it says now - otherwise a status
        // that changed while the answer was printing would not appear until the next tick.
        if (held != null) render(held);
    }

    private void eraseRawStatusRow() {
        if (terminal != null && "dumb".equals(terminal.getType())) return;
        synchronized (outputLock) {
            if (closed) return;
            output.print("\r\u001B[2K");
            output.flush();
        }
    }

    @Override
    public void workspaceChanged(java.nio.file.Path moved) {
        this.workspace = label(moved);
        idle();
    }

    @Override
    public void configurationChanged(String model, int contextSize) {
        this.model = model;
        this.contextSize = contextSize;
        lastContextPercent = 0;
        lastEvalTokensPerSecond = 0;
        lastEvalRateApproximate = false;
        idle();
    }

    private static String label(java.nio.file.Path workspace) {
        return workspace.getFileName() == null
                ? workspace.toString()
                : workspace.getFileName().toString();
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

    private String withEvalRate(String line) {
        double rate = lastEvalTokensPerSecond;
        if (rate > 0) {
            line = line + String.format(
                    Locale.ROOT,
                    lastEvalRateApproximate ? " │ ~%.2f tok/s" : " │ %.2f tok/s",
                    rate
            );
        }
        java.util.function.Supplier<int[]> counts = subagentCounts;
        if (counts != null) {
            int[] value = counts.get();
            if (value != null && value.length == 2) {
                line = line + " │ sub " + value[0] + "/" + value[1];
            }
        }
        return line;
    }

    private void render(String rawLine) {
        // The answer is printed word by word with a pause between the words, and the status ticker
        // runs on its own thread. Both take the terminal lock, so neither can land inside the
        // other's write - but nothing stopped one landing *between* two words, which put the row
        // into the middle of a shell script the model had just written. Hold it instead.
        if (outputInProgress.get() > 0) {
            heldLine = rawLine;
            return;
        }
        String line = glyphs.downgrade(rawLine);
        if (useJLine()) {
            renderViaJLine(clampToWidth(line));
        } else if (terminal != null && "dumb".equals(terminal.getType())) {
            renderViaDumbTerminal(line);
        } else {
            renderViaRaw(clampToWidth(line));
        }
    }

    /** Keeps the status within one row. */
    String clampToWidth(String line) {
        int columns = terminal == null ? 0 : terminal.getSize().getColumns();
        // One column spare: writing into the last cell makes some terminals wrap anyway.
        if (columns <= 1 || line.length() <= columns - 1) return line;
        return line.substring(0, columns - 1);
    }

    private boolean useJLine() {
        return terminal != null
                && terminalStatus != null
                && !"dumb".equals(terminal.getType())
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

    /**
     * A dumb terminal cannot rewrite a row, so a status per tick buries the conversation under
     * near-identical lines.
     */
    private void renderViaDumbTerminal(String line) {
        // Intentionally nothing.
    }

    private void activityLine(String rawLine) {
        // A sub-agent reports on its own thread, so this can arrive mid-answer just as the status
        // row could. Queued rather than dropped: what a sub-agent did is worth reading late.
        if (outputInProgress.get() > 0) {
            heldActivity.add(rawLine);
            return;
        }
        String line = glyphs.downgrade(rawLine);
        if (useJLine()) {
            synchronized (terminal) {
                if (closed) return;
                terminalStatus.suspend();
                terminal.writer().println(new AttributedString(
                        line, theme.style(ThemeSettings.Element.ACTIVITY)
                ).toAnsi(terminal));
                terminalStatus.restore();
                terminal.flush();
            }
            return;
        }
        synchronized (outputLock) {
            if (closed) return;
            String rendered = terminal == null ? line : new AttributedString(
                    line, theme.style(ThemeSettings.Element.ACTIVITY)
            ).toAnsi(terminal);
            output.print("\r\u001B[2K" + rendered + System.lineSeparator());
            output.flush();
        }
    }

    private static String activityValue(String value) {
        if (value == null) return "unknown";
        String safe = value.replaceAll("[^a-zA-Z0-9._-]", "");
        return safe.isBlank() ? "unknown" : safe;
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
        if (!supportsJLineStatus(terminal)) {
            return null;
        }
        synchronized (terminal) {
            terminal.writer().print(CLEAR_SCREEN + CURSOR_HOME);
            terminal.flush();
            return Status.getStatus(terminal);
        }
    }

    static boolean supportsJLineStatus(Terminal terminal) {
        return describeStatusSupport(terminal).supported();
    }

    /** Why the pinned status row is or is not available, for {@code /doctor}. */
    public record StatusSupport(boolean supported, String reason) {}

    /**
     * A console reporting no size is not necessarily incapable - Windows ones return 0x0 - so an
     * implausible size counts as unknown, not as refusal.
     */
    public static StatusSupport describeStatusSupport(Terminal terminal) {
        if (terminal == null) return new StatusSupport(false, "no terminal (not interactive)");
        if ("dumb".equals(terminal.getType())) {
            return new StatusSupport(false, "dumb terminal: cannot address the cursor");
        }
        String missing = missingCapabilities(terminal);
        if (!missing.isEmpty()) {
            return new StatusSupport(false, "terminal lacks " + missing);
        }
        var size = terminal.getSize();
        boolean plausible = size.getRows() > 0 && size.getRows() < 1_000
                && size.getColumns() > 0 && size.getColumns() < 1_000;
        return new StatusSupport(true, plausible
                ? "pinned row (" + size.getColumns() + "x" + size.getRows() + ")"
                : "pinned row (terminal reported no usable size; assuming it is capable)");
    }

    private static String missingCapabilities(Terminal terminal) {
        StringBuilder missing = new StringBuilder();
        for (InfoCmp.Capability capability : new InfoCmp.Capability[]{
                InfoCmp.Capability.change_scroll_region,
                InfoCmp.Capability.save_cursor,
                InfoCmp.Capability.restore_cursor,
                InfoCmp.Capability.cursor_address}) {
            if (terminal.getStringCapability(capability) == null) {
                if (missing.length() > 0) missing.append(", ");
                missing.append(capability.name());
            }
        }
        return missing.toString();
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
