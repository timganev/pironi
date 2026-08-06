package dev.pironi.model;

public record ModelResponse(
        String content,
        long promptTokens,
        long outputTokens,
        long durationNanos,
        long evalDurationNanos
) {
    public ModelResponse(String content, long promptTokens, long outputTokens, long durationNanos) {
        this(content, promptTokens, outputTokens, durationNanos, 0);
    }

    public ModelResponse {
        if (content == null) {
            content = "";
        }
    }
}
