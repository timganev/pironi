package dev.pironi.tool;

/** Shared limits for what a single tool result may put into the context. */
public final class ToolOutput {
    /**
     * How many characters one tool result may contribute. Declared once: the same rule used to
     * live in five places, three of them bare literals in the wiring, so raising it in one was
     * silently overridden by the others.
     */
    public static final int MAX_CHARACTERS = 32_000;

    private ToolOutput() {
    }
}
