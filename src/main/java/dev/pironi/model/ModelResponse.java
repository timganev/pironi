package dev.pironi.model;

public record ModelResponse(
        String content,
        long promptTokens,
        long outputTokens,
        long durationNanos,
        long evalDurationNanos,
        String finishReason,
        String responseFormat
) {
    public ModelResponse(String content, long promptTokens, long outputTokens, long durationNanos) {
        this(content, promptTokens, outputTokens, durationNanos, 0, "unknown", "unknown");
    }

    public ModelResponse(String content, long promptTokens, long outputTokens,
            long durationNanos, long evalDurationNanos) {
        this(content, promptTokens, outputTokens, durationNanos, evalDurationNanos,
                "unknown", "unknown");
    }

    public ModelResponse(String content, long promptTokens, long outputTokens,
            long durationNanos, long evalDurationNanos, String finishReason) {
        this(content, promptTokens, outputTokens, durationNanos, evalDurationNanos,
                finishReason, "unknown");
    }

    public ModelResponse {
        if (content == null) {
            content = "";
        }
        if (finishReason == null || finishReason.isBlank()) finishReason = "unknown";
        if (responseFormat == null || responseFormat.isBlank()) responseFormat = "unknown";
    }

    public boolean truncated() {
        return finishReason.equalsIgnoreCase("length")
                || finishReason.equalsIgnoreCase("max_tokens");
    }
}
