package dev.pironi.model;

import java.net.URI;
import java.time.Duration;

public record ProviderConfig(
        ProviderType type,
        URI baseUri,
        String model,
        String apiKey,
        Duration timeout,
        int contextSize,
        int maxOutputTokens
) {
    public ModelClient createClient() {
        return switch (type) {
            case OLLAMA -> new OllamaClient(
                    baseUri,
                    model,
                    timeout,
                    contextSize,
                    maxOutputTokens
            );
            case DEEPSEEK -> OpenAiCompatibleClient.deepSeek(
                    baseUri,
                    model,
                    apiKey,
                    timeout,
                    maxOutputTokens
            );
            case OPENROUTER, OPENAI_COMPATIBLE -> new OpenAiCompatibleClient(
                    baseUri,
                    model,
                    apiKey,
                    timeout,
                    maxOutputTokens
            );
        };
    }
}
