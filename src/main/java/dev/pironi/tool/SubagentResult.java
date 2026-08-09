package dev.pironi.tool;

import java.util.List;

/**
 * Final outcome of a spawned sub-agent, ready to be drained into the main loop.
 *
 * @param activity child tool activity lines (e.g. {@code t1 http_get ok 412ms ...}),
 *                 used for observability and model attribution via the envelope
 */
public record SubagentResult(String id, String name, String status, String output, List<String> activity) {
    public SubagentResult {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("subagent id must not be blank");
        }
        status = status == null ? "completed" : status;
        output = output == null ? "" : output;
        activity = activity == null ? List.of() : List.copyOf(activity);
    }

    public static SubagentResult completed(String id, String name, String output) {
        return new SubagentResult(id, name, "completed", output, List.of());
    }

    public static SubagentResult completed(String id, String name, String output, List<String> activity) {
        return new SubagentResult(id, name, "completed", output, activity);
    }

    public static SubagentResult error(String id, String name, String detail) {
        return new SubagentResult(id, name, "error", detail, List.of());
    }
}
