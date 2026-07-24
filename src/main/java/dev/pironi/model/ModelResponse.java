package dev.pironi.model;

public record ModelResponse(
        String content,
        long promptTokens,
        long outputTokens,
        long durationNanos
) {
    public ModelResponse {
        if (content == null) {
            content = "";
        }
    }
}
