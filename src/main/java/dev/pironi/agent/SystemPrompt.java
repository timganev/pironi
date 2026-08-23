package dev.pironi.agent;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Text the harness ships rather than computes: the agent's instructions, the summarisation prompt,
 * the usage screen. Classpath only - a path on disk would let a document inside the workspace
 * rewrite the system prompt.
 */
public final class SystemPrompt {
    private static final String SYSTEM = "/prompts/system.md";
    private static final String COMPRESSION = "/prompts/compression.md";
    private static final String USAGE = "/usage.txt";

    private SystemPrompt() {
    }

    /**
     * @throws IllegalStateException when the resource is missing or empty - an empty system prompt
     *         throws nothing on its own and shows up only as an agent that stopped following its
     *         instructions, which is expensive to diagnose and easy to miss
     */
    public static String load() {
        return read(SYSTEM);
    }

    /** The summarisation instructions, kept beside the system prompt for the same reason. */
    public static String compression() {
        return read(COMPRESSION);
    }

    /** The --help text: prose, and the only thing in the jar a user reads before running it. */
    public static String usage() {
        return read(USAGE);
    }

    private static String read(String resource) {
        try (InputStream stream = SystemPrompt.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("Missing from the jar: " + resource);
            }
            String text = new String(stream.readAllBytes(), StandardCharsets.UTF_8).strip();
            if (text.isEmpty()) {
                throw new IllegalStateException("Empty in the jar: " + resource);
            }
            return text;
        } catch (IOException e) {
            // Every one of these said "System prompt could not be read" whichever resource it was,
            // and carried nothing of the cause: the trace records getMessage(), not the chain. A
            // run of these turned up in the trace naming a file that was present the whole time,
            // and there was no way to tell from the record what had actually failed. The usual
            // cause is the jar being rebuilt underneath a running process, which is worth saying
            // outright rather than leaving to be rediscovered.
            throw new UncheckedIOException(
                    resource + " could not be read from the jar (" + e.getClass().getSimpleName()
                            + ": " + e.getMessage() + "). A jar replaced while it is running gives"
                            + " exactly this; restart rather than reading it as a missing file.",
                    e);
        }
    }
}
