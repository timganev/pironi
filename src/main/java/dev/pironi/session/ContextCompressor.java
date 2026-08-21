package dev.pironi.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.pironi.agent.SystemPrompt;
import dev.pironi.model.ChatMessage;

import java.util.ArrayList;
import java.util.List;

/** Model-driven context compression. */
public final class ContextCompressor {
    private static final int MAX_SUMMARY_CHARACTERS = 2_400;
    private final int contextLimit;
    private final ObjectMapper mapper;
    private long runningPromptTokens;
    private long runningOutputTokens;
    // Half the window, not 70%: the ledgers re-rendered each turn accumulate stale copies in the
    // history, and at the old default a 131k-token run reached its turn limit without ever
    // compressing them away.
    private double threshold = 0.50;
    private boolean enabled = true;
    private String lastSummary = "";

    public ContextCompressor(int contextLimit, ObjectMapper mapper) {
        this.contextLimit = contextLimit;
        this.mapper = mapper;
    }

    // ── token tracking ─────────────────────────────────────────────────

    public void addTokens(long prompt, long output) {
        // Provider prompt usage already includes the conversation sent on this request.
        runningPromptTokens = Math.max(0, prompt);
        runningOutputTokens = Math.max(0, output);
    }

    public long usedTokens() { return runningPromptTokens + runningOutputTokens; }
    public int contextLimit() { return contextLimit; }
    public double threshold() { return threshold; }
    public boolean enabled() { return enabled; }
    public String lastSummary() { return lastSummary; }

    public void setThreshold(double t) { this.threshold = Math.clamp(t, 0.20, 0.95); }
    public void setEnabled(boolean e) { this.enabled = e; }

    public void reset() {
        runningPromptTokens = 0;
        runningOutputTokens = 0;
        lastSummary = "";
    }

    public boolean shouldCompress() {
        return enabled && usedTokens() > (long) (contextLimit * threshold);
    }

    public double usagePercent() {
        return usedTokens() * 100.0 / contextLimit;
    }

    // ── semantic compression prompt ────────────────────────────────────

    /** Builds a compression prompt for the model. */
    public String buildCompressionPrompt(List<ChatMessage> messages, String task) {
        // Skip system prompt (index 0) and keep last 4 messages verbatim
        int skipHead = 1; // system prompt
        int keepTail = 4;
        int compressEnd = Math.max(skipHead, messages.size() - keepTail);

        if (compressEnd <= skipHead) return null; // nothing to compress

        StringBuilder toCompress = new StringBuilder();
        for (int i = skipHead; i < compressEnd; i++) {
            ChatMessage m = messages.get(i);
            toCompress.append("[").append(m.role()).append("]: ")
                    .append(truncate(m.content(), 1000))
                    .append("\n\n");
        }

        return SystemPrompt.compression()
                .replace("{{task}}", task)
                .replace("{{conversation}}", toCompress.toString());
    }

    /** Stores the compressed summary and resets token counters to reflect new state. */
    public String storeSummary(String summary) {
        this.lastSummary = boundSummary(summary);
        // Reset: system + task (~500) + summary (~300) + last 4 turns (~800) ≈ 1600 base
        runningPromptTokens = Math.min(1600, contextLimit / 2L);
        runningOutputTokens = 0;
        return """
               [COMPRESSED CONTEXT]
               The conversation above this point has been summarized.
               Previous key points:
               %s
               
               Continue from here with the recent conversation below.
               """.formatted(lastSummary);
    }

    /** Restores only bounded working state; summaries never become identity instructions. */
    public void restoreSummary(String summary) {
        lastSummary = boundSummary(summary);
        runningPromptTokens = Math.min(1600, contextLimit / 2L);
        runningOutputTokens = 0;
    }

    // ── checkpoint ─────────────────────────────────────────────────────

    public String toCheckpointJson(String task, String model) {
        ObjectNode node = mapper.createObjectNode();
        node.put("compressed", true);
        node.put("model", model);
        node.put("task", task);
        node.put("summary", lastSummary);
        node.put("promptTokens", runningPromptTokens);
        node.put("outputTokens", runningOutputTokens);
        node.put("threshold", threshold);
        return node.toString();
    }

    // ── helpers ────────────────────────────────────────────────────────

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static String boundSummary(String summary) {
        if (summary == null || summary.isBlank()) return "";
        String normalized = summary.strip();
        return normalized.length() <= MAX_SUMMARY_CHARACTERS
                ? normalized
                : normalized.substring(0, MAX_SUMMARY_CHARACTERS);
    }
}
