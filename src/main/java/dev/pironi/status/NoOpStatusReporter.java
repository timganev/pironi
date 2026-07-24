package dev.pironi.status;

import dev.pironi.model.ChatMessage;

import java.util.List;

public final class NoOpStatusReporter implements StatusReporter {
    private static final Activity NO_OP_ACTIVITY = () -> {
    };

    @Override
    public Activity thinking(int turn, List<ChatMessage> messages) {
        return NO_OP_ACTIVITY;
    }

    @Override
    public void tool(String toolName) {
    }

    @Override
    public void idle() {
    }
}
