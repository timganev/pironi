package dev.pironi.agent;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Text the harness ships rather than computes: the agent's instructions, the summarisation
 * prompt, the usage screen. As Java literals these were content edits that recompiled the harness
 * and showed up as Java diffs, which is the worst place to review prose.
 *
 * <p>Read from the classpath only. A path on disk would let a document inside the workspace
 * rewrite the system prompt, which is the one string nothing else should be able to reach.
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
                throw new IllegalStateException("System prompt missing from the jar: " + resource);
            }
            String text = new String(stream.readAllBytes(), StandardCharsets.UTF_8).strip();
            if (text.isEmpty()) {
                throw new IllegalStateException("System prompt is empty: " + resource);
            }
            return text;
        } catch (IOException e) {
            throw new UncheckedIOException("System prompt could not be read: " + resource, e);
        }
    }
}
