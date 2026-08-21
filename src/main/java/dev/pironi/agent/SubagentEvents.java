package dev.pironi.agent;

import dev.pironi.tool.SubagentResult;

/**
 * Sink for sub-agent lifecycle events so the user is kept informed ("пускам агент X…") and can keep
 * talking while the child runs.
 */
public interface SubagentEvents {
    /** Called immediately before a child is submitted, from the spawner thread. */
    void onSpawn(String name, String task);

    /** Called from the child's virtual thread when it finishes (after enqueue). */
    void onDone(SubagentResult result);

    SubagentEvents NOOP = new SubagentEvents() {
        @Override public void onSpawn(String name, String task) { }
        @Override public void onDone(SubagentResult result) { }
    };

    /** Pure text formatting, kept testable without a terminal. */
    final class Formatting {
        private Formatting() { }

        public static String spawn(String name, String task) {
            return "⏳ пускам агент «" + name + "»" + shortTask(task)
                    + "… продължавай да пишеш, ще те известя.";
        }

        public static String done(SubagentResult r, long seconds, String summary) {
            if ("error".equals(r.status())) {
                return "⚠️ агент «" + r.name() + "» се провали: " + cut(summary, 120);
            }
            if (r.isCancelled()) {
                return "⏹ агент «" + r.name() + "» беше спрян (" + seconds + "s) "
                        + "— не завърши; резултатът е частичен или липсва.";
            }
            if ("timeout".equals(r.status())) {
                return "⏱ агент «" + r.name() + "» изтече (" + seconds + "s) — прекратен; "
                        + "не е приключил, резултатът е частичен или липсва.";
            }
            return "✅ агент «" + r.name() + "» приключи за " + seconds + "s: "
                    + cut(summary, 160) + " — виж пълния резултат при следващото ти съобщение.";
        }

        private static String shortTask(String task) {
            if (task == null || task.isBlank()) return "";
            String t = task.replaceAll("[\\r\\n]+", " ").trim();
            return t.length() <= 60 ? ": " + t : ": " + t.substring(0, 57) + "…";
        }

        private static String cut(String s, int max) {
            if (s == null) return "";
            String single = s.replaceAll("[\\r\\n]+", " ").trim();
            return single.length() <= max ? single : single.substring(0, max - 1) + "…";
        }
    }
}
