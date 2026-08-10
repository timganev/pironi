package dev.pironi.tool;

import java.util.List;

/**
 * Final outcome of a spawned sub-agent, ready to be drained into the main loop.
 *
 * @param activity child tool activity lines (e.g. {@code t1 http_get ok 412ms ...}),
 *                 used for observability and model attribution via the envelope
 */
public record SubagentResult(String id, String name, String status, String output,
                             List<String> activity, java.time.Duration elapsed) {
    /** Why a child was cancelled before it could finish. {@link #cancelled}. */
    public enum CancelReason { NONE, DISCARDED, TIMEOUT, SHUTDOWN }

    public SubagentResult {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("subagent id must not be blank");
        }
        status = status == null ? "completed" : status;
        output = output == null ? "" : output;
        activity = activity == null ? List.of() : List.copyOf(activity);
        elapsed = elapsed == null ? java.time.Duration.ZERO : elapsed;
    }

    public static SubagentResult completed(String id, String name, String output) {
        return new SubagentResult(id, name, "completed", output, List.of(), java.time.Duration.ZERO);
    }

    public static SubagentResult completed(String id, String name, String output, List<String> activity) {
        return new SubagentResult(id, name, "completed", output, activity, java.time.Duration.ZERO);
    }

    public static SubagentResult error(String id, String name, String detail) {
        return new SubagentResult(id, name, "error", detail, List.of(), java.time.Duration.ZERO);
    }

    /** A child that was cancelled (discarded, timed out, or shut down) before finishing. */
    public static SubagentResult cancelled(String id, String name, CancelReason reason) {
        CancelReason r = reason == null ? CancelReason.NONE : reason;
        return new SubagentResult(id, name, "cancelled", r.name(), List.of(), java.time.Duration.ZERO);
    }

    /** Convenience: true when this result represents a cancellation, not an ordinary error. */
    public boolean isCancelled() {
        return "cancelled".equals(status);
    }
}
