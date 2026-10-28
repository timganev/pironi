package dev.pironi.swarm;

/**
 * What the dispatcher has learned the server takes, for whoever plans the work - the
 * orchestrator of phase 4 asks this before it splits a task.
 *
 * <p>The rule it must follow: at a limit of 1 a task is not split. One agent works alone, because
 * on a server that runs one request at a time sub-agents only queue behind each other and add the
 * cost of briefing and merging.
 *
 * @param limit   requests the server is believed to run at once, at least 1
 * @param settled whether the search has finished; before that the limit may still grow
 * @param ceiling the most it will ever be: --max-parallel, the server's own slot count, or a safety cap
 */
public record Parallelism(int limit, boolean settled, int ceiling) {
    /** Whether splitting a task into sub-agents can pay off on this server at all. */
    public boolean worthSplitting() {
        return limit > 1;
    }
}
