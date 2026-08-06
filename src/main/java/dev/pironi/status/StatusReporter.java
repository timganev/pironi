package dev.pironi.status;

import dev.pironi.model.ChatMessage;
import dev.pironi.model.ModelResponse;

import java.util.List;

public interface StatusReporter extends AutoCloseable {
    Activity thinking(int turn, List<ChatMessage> messages);

    void tool(String toolName);

    void idle();

    default void modelResponse(ModelResponse response) {
    }

    default void configurationChanged(String model, int contextSize) {
    }

    @Override
    default void close() {
    }

    interface Activity extends AutoCloseable {
        @Override
        void close();
    }
}
