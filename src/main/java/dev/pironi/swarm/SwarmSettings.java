package dev.pironi.swarm;

import java.nio.file.Path;

/**
 * The swarm dispatcher's knobs. Off unless asked for with --swarm: the dispatcher reads the
 * server's /metrics and holds requests back, which only makes sense against a local server whose
 * load is ours to manage.
 *
 * @param enabled      whether model calls go through the dispatcher at all
 * @param maxWindow    the most requests the window may grow to
 * @param budgetTokens uncached prompt tokens allowed in prefill at once; the ceiling AIMD returns to
 * @param logPath      where state changes are written, or null for none
 */
public record SwarmSettings(boolean enabled, int maxWindow, long budgetTokens, Path logPath) {
    /** vLLM on the B70 runs 8 sequences at once (max_num_seqs); a narrower ceiling only idles it. */
    public static final int DEFAULT_MAX_WINDOW = 8;
    public static final long DEFAULT_BUDGET_TOKENS = 16_000;

    public static SwarmSettings off() {
        return new SwarmSettings(false, DEFAULT_MAX_WINDOW, DEFAULT_BUDGET_TOKENS, null);
    }
}
