package dev.pironi.model;

public enum ProviderType {
    OLLAMA,
    DEEPSEEK,
    OPENROUTER,
    OPENAI_COMPATIBLE;

    public static ProviderType parse(String value) {
        return switch (value.toLowerCase()) {
            case "ollama" -> OLLAMA;
            case "deepseek" -> DEEPSEEK;
            case "openrouter" -> OPENROUTER;
            case "openai", "openai-compatible" -> OPENAI_COMPATIBLE;
            default -> throw new IllegalArgumentException(
                    "Unknown provider: " + value
                            + " (expected ollama, deepseek, openrouter, or openai-compatible)"
            );
        };
    }
}
