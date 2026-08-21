package dev.pironi.tool;

/** Shared limits for what a single tool result may put into the context. */
public final class ToolOutput {
    /** How many characters one tool result may contribute. */
    public static final int MAX_CHARACTERS = 32_000;

    private ToolOutput() {
    }
}
