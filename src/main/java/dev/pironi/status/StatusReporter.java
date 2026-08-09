package dev.pironi.status;

import dev.pironi.model.ChatMessage;
import dev.pironi.model.ModelResponse;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

public interface StatusReporter extends AutoCloseable {
    Activity thinking(int turn, List<ChatMessage> messages);

    void tool(String toolName);

    default void skill(String skillName) {
    }

    default void toolStarted(String toolName, JsonNode arguments) {
        tool(toolName);
    }

    default void toolFinished(String toolName, boolean success, long durationMillis) {
    }

    void idle();

    default void modelResponse(ModelResponse response) {
    }

    default void outputStarted() {
    }

    default void outputFinished() {
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
