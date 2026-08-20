package dev.pironi.model;

public record ModelResponse(
        String content,
        long promptTokens,
        long outputTokens,
        long durationNanos,
        long evalDurationNanos,
        String finishReason,
        String responseFormat,
        int requestAttempts,
        String fallbackFrom,
        String fallbackReason,
        long wallClockNanos
) {
    /**
     * Providers report only the time they spent generating, which leaves out queueing, loading a
     * model back into memory, and the request itself. One turn measured this way reported 79
     * seconds against 766 seconds of real waiting, so a run timed on the reported number is not
     * timed at all. Zero means nobody measured.
     */
    public ModelResponse(String content, long promptTokens, long outputTokens,
            long durationNanos, long evalDurationNanos, String finishReason,
            String responseFormat, int requestAttempts, String fallbackFrom,
            String fallbackReason) {
        this(content, promptTokens, outputTokens, durationNanos, evalDurationNanos,
                finishReason, responseFormat, requestAttempts, fallbackFrom, fallbackReason, 0);
    }

    public ModelResponse(String content, long promptTokens, long outputTokens, long durationNanos) {
        this(content, promptTokens, outputTokens, durationNanos, 0, "unknown", "unknown", 1, "", "", 0);
    }

    public ModelResponse(String content, long promptTokens, long outputTokens,
            long durationNanos, long evalDurationNanos) {
        this(content, promptTokens, outputTokens, durationNanos, evalDurationNanos,
                "unknown", "unknown", 1, "", "", 0);
    }

    public ModelResponse(String content, long promptTokens, long outputTokens,
            long durationNanos, long evalDurationNanos, String finishReason) {
        this(content, promptTokens, outputTokens, durationNanos, evalDurationNanos,
                finishReason, "unknown", 1, "", "", 0);
    }

    public ModelResponse(String content, long promptTokens, long outputTokens,
            long durationNanos, long evalDurationNanos, String finishReason,
            String responseFormat) {
        this(content, promptTokens, outputTokens, durationNanos, evalDurationNanos,
                finishReason, responseFormat, 1, "", "", 0);
    }

    public ModelResponse {
        if (content == null) {
            content = "";
        }
        if (finishReason == null || finishReason.isBlank()) finishReason = "unknown";
        if (responseFormat == null || responseFormat.isBlank()) responseFormat = "unknown";
        if (requestAttempts < 1) requestAttempts = 1;
        if (fallbackFrom == null) fallbackFrom = "";
        if (fallbackReason == null) fallbackReason = "";
    }

    public boolean truncated() {
        return finishReason.equalsIgnoreCase("length")
                || finishReason.equalsIgnoreCase("max_tokens");
    }
}
