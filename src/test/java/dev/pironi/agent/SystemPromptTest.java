package dev.pironi.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemPromptTest {
    @Test
    void theResourceIsPackagedAndCarriesTheInstructions() {
        // An empty or missing system prompt throws nothing by itself. It shows up as an agent
        // that quietly stopped following its instructions, so it is asserted rather than trusted.
        String prompt = SystemPrompt.load();

        assertTrue(prompt.startsWith("You are Pironi"), prompt.substring(0, 40));
        assertTrue(prompt.contains("Never claim success without verification"), "verification rule");
        assertTrue(prompt.contains("Runtime capabilities"), "capabilities rule");
        assertTrue(prompt.length() > 3_000, "length was " + prompt.length());
    }

    @Test
    void theToolPlaceholderIsPresentAndSubstituted() {
        String prompt = SystemPrompt.load();
        assertTrue(prompt.contains("{{tools}}"), "placeholder missing from the resource");

        String filled = prompt.replace("{{tools}}", "- read_file: reads");
        assertFalse(filled.contains("{{tools}}"));
        assertTrue(filled.contains("- read_file: reads"));
    }

    @Test
    void everyShippedTextIsPresentAndUsable() {
        // A missing resource throws nothing until the moment it is needed, and the compression
        // prompt is needed only on a long conversation - hours into a run.
        String compression = SystemPrompt.compression();
        assertTrue(compression.contains("{{task}}"), compression);
        assertTrue(compression.contains("{{conversation}}"), compression);
        assertTrue(compression.contains("Compress the conversation"), compression);

        String usage = SystemPrompt.usage();
        assertTrue(usage.startsWith("Pironi - small Java 25 coding agent harness"), usage);
        assertTrue(usage.contains("--workspace"), "the usage screen lost an option");
        assertTrue(usage.contains("--read-scope"), "the usage screen lost an option");
    }
}
