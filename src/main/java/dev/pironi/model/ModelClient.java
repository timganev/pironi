package dev.pironi.model;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

@FunctionalInterface
public interface ModelClient {
    ModelResponse chat(List<ChatMessage> messages) throws IOException, InterruptedException;

    default ModelResponse chatStreaming(
            List<ChatMessage> messages,
            Consumer<String> contentChunk
    ) throws IOException, InterruptedException {
        ModelResponse response = chat(messages);
        contentChunk.accept(response.content());
        return response;
    }
}
