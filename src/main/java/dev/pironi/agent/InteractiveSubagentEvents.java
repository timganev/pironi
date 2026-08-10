package dev.pironi.agent;

import dev.pironi.tool.SubagentResult;

import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link SubagentEvents} that renders to the interactive terminal via JLine
 * {@code printAbove}. When a child finishes, the optional auto‑turn callback fires so the
 * model can process the collected result without waiting for the user to press Enter.
 * The callback is installed later (once the JLine shell is ready) via
 * {@link #setAutoTurn(Runnable)}.
 */
public final class InteractiveSubagentEvents implements SubagentEvents {
    private final Object printLock = new Object();
    private final java.util.function.Consumer<String> printer;
    private final AtomicReference<Runnable> autoTurn = new AtomicReference<>(() -> {});

    public InteractiveSubagentEvents(java.util.function.Consumer<String> printer) {
        this.printer = printer;
    }

    /** Install the live auto‑turn callback once the JLine shell exists. */
    public void setAutoTurn(Runnable callback) {
        if (callback != null) autoTurn.set(callback);
    }

    @Override
    public void onSpawn(String name, String task) {
        synchronized (printLock) {
            printer.accept(Formatting.spawn(name, task));
        }
    }

    @Override
    public void onDone(SubagentResult result) {
        String line = Formatting.done(result, elapsedSeconds(result), summarize(result.output()));
        synchronized (printLock) {
            printer.accept(line);
        }
        // Fire the auto‑turn callback so the model processes the result immediately.
        autoTurn.get().run();
    }

    private static long elapsedSeconds(SubagentResult result) {
        return result.elapsed().toSeconds();
    }

    private static String summarize(String output) {
        if (output == null) return "";
        return output.replaceAll("[\\r\\n]+", " ").trim();
    }
}
