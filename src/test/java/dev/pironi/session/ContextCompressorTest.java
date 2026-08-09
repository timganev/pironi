package dev.pironi.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.pironi.model.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContextCompressorTest {
    @Test void tracksThresholdAndResetsAfterSummary() {
        ContextCompressor compressor = new ContextCompressor(1000, new ObjectMapper());
        compressor.addTokens(500, 201);
        assertTrue(compressor.shouldCompress());
        assertEquals(70.1, compressor.usagePercent(), 0.01);
        assertTrue(compressor.storeSummary("summary").contains("summary"));
        assertEquals("summary", compressor.lastSummary());
        assertEquals(500, compressor.usedTokens());
    }

    @Test void tracksCurrentContextInsteadOfCumulativeProviderSpend() {
        ContextCompressor compressor = new ContextCompressor(1000, new ObjectMapper());
        compressor.addTokens(300, 20);
        compressor.addTokens(340, 20);
        compressor.addTokens(380, 20);

        assertEquals(400, compressor.usedTokens());
        assertFalse(compressor.shouldCompress());
    }

    @Test void boundsModelGeneratedSummary() {
        ContextCompressor compressor = new ContextCompressor(10_000, new ObjectMapper());
        String stored = compressor.storeSummary("x".repeat(20_000));

        assertTrue(compressor.lastSummary().length() <= 2_400);
        assertFalse(stored.contains("x".repeat(2_401)));
    }

    @Test void buildsPromptOnlyWhenThereIsOldHistory() {
        ContextCompressor compressor = new ContextCompressor(10_000, new ObjectMapper());
        assertNull(compressor.buildCompressionPrompt(List.of(
                ChatMessage.system("s"), ChatMessage.user("u"), ChatMessage.assistant("a")
        ), "task"));
        String prompt = compressor.buildCompressionPrompt(List.of(
                ChatMessage.system("s"), ChatMessage.user("old1"),
                ChatMessage.assistant("old2"), ChatMessage.user("r1"),
                ChatMessage.assistant("r2"), ChatMessage.user("r3"),
                ChatMessage.assistant("r4")
        ), "goal");
        assertNotNull(prompt);
        assertTrue(prompt.contains("old1"));
        assertFalse(prompt.contains("r4"));
    }

    @Test void clampsThresholdAndCanBeDisabled() {
        ContextCompressor compressor = new ContextCompressor(100, new ObjectMapper());
        compressor.setThreshold(2);
        assertEquals(.95, compressor.threshold());
        compressor.addTokens(100, 0);
        compressor.setEnabled(false);
        assertFalse(compressor.shouldCompress());
        compressor.reset();
        assertEquals(0, compressor.usedTokens());
        assertEquals("", compressor.lastSummary());
    }
}
