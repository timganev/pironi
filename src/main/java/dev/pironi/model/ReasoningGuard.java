package dev.pironi.model;

/**
 * Server-side limits on a thinking model that can reason in a circle.
 *
 * <p>A local model at temperature 0 sometimes thinks until it hits the output limit and never
 * begins the answer: eight thousand tokens and forty seconds for an empty reply that used to end
 * the turn. A thinking budget makes the server close the reasoning and ask for the answer;
 * repetition detection stops a reply whose tail repeats itself word for word. When either still
 * leaves the answer empty, the client asks once more without thinking.
 *
 * @param thinkingTokenBudget reasoning tokens before the server ends the thinking; 0 is off
 * @param stopRepetition whether the server stops a reply whose tail keeps repeating
 */
public record ReasoningGuard(int thinkingTokenBudget, boolean stopRepetition) {
    public static final ReasoningGuard OFF = new ReasoningGuard(0, false);

    /** Four identical runs of 8 to 64 tokens in a row: a loop, not a list. */
    static final int REPETITION_MIN_PATTERN = 8;
    static final int REPETITION_MAX_PATTERN = 64;
    static final int REPETITION_MIN_COUNT = 4;

    public ReasoningGuard {
        if (thinkingTokenBudget < 0) {
            throw new IllegalArgumentException("thinking token budget must not be negative");
        }
    }

    public boolean enabled() {
        return thinkingTokenBudget > 0 || stopRepetition;
    }
}
