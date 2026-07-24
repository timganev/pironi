package dev.pironi.model;

import java.io.IOException;
import java.util.List;

@FunctionalInterface
public interface ModelClient {
    ModelResponse chat(List<ChatMessage> messages) throws IOException, InterruptedException;
}
