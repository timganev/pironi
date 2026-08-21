package dev.pironi.agent;

import dev.pironi.tool.SubagentResult;

import java.util.concurrent.atomic.AtomicReference;

/** {@link SubagentEvents} rendered to the terminal through JLine {@code printAbove}. */
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
