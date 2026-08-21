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

    /**
     * A failure the user can act on. "Failed run_command in 1 ms" says nothing, and the reason
     * the harness already holds - a refused scope, a missing file - never reached the screen; a
     * user watching a refused sed had no way to tell it from a broken command.
     */
    default void toolFinished(String toolName, boolean success, long durationMillis, String why) {
        toolFinished(toolName, success, durationMillis);
    }

    void idle();

    default void modelResponse(ModelResponse response) {
    }

    default void outputStarted() {
    }

    default void outputFinished() {
    }

    /**
     * The workspace moved. The status row names the directory being worked in, and it was read
     * once at startup: after /workspace or switch_workspace the row kept naming the directory
     * that had been left behind, which is the one thing the row exists to answer.
     */
    default void workspaceChanged(java.nio.file.Path workspace) {
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
