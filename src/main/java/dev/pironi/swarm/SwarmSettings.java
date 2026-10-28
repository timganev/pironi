package dev.pironi.swarm;

import java.nio.file.Path;

/**
 * The swarm dispatcher's knobs. Off unless asked for with --swarm: the dispatcher holds requests
 * back by how the server answers, which only makes sense against a server whose load is ours to
 * manage.
 *
 * @param enabled      whether model calls go through the dispatcher at all
 * @param maxParallel  the most requests ever in flight; 0 learns it (and takes the server's slot
 *                     count as the ceiling where the server tells it)
 * @param budgetTokens uncached prompt tokens allowed in prefill at once, when /metrics is read
 * @param blind        never read /metrics; latency alone sets the limit, as on any other server
 * @param logPath      where state changes are written, or null for none
 * @param profilePath  where the limit learned per server and model is kept, or null for nowhere
 */
public record SwarmSettings(boolean enabled, int maxParallel, long budgetTokens, boolean blind, Path logPath,
                            Path profilePath) {
    public static final long DEFAULT_BUDGET_TOKENS = 16_000;

    public static SwarmSettings off() {
        return new SwarmSettings(false, 0, DEFAULT_BUDGET_TOKENS, false, null, null);
    }
}
