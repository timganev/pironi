package dev.pironi.tool;

import com.fasterxml.jackson.databind.JsonNode;
import dev.pironi.safety.CheckpointManager;

import java.io.IOException;

public final class RollbackCheckpointTool implements Tool {
    private final CheckpointManager checkpointManager;

    public RollbackCheckpointTool(CheckpointManager checkpointManager) {
        this.checkpointManager = checkpointManager;
    }

    @Override
    public String name() {
        return "rollback_checkpoint";
    }

    @Override
    public String description() {
        return "Restore the file from the latest patch checkpoint in this session.";
    }

    @Override
    public String argumentSchema() {
        return "{}";
    }

    @Override
    public boolean mutating() {
        return true;
    }

    @Override
    public String approvalPreview(JsonNode arguments) {
        return checkpointManager.latest()
                .map(checkpoint -> "Restore " + checkpoint.relativePath()
                        + " from checkpoint " + checkpoint.id())
                .orElse("No checkpoint is available");
    }

    @Override
    public ToolResult execute(JsonNode arguments) {
        try {
            var checkpoint = checkpointManager.rollbackLatest();
            return ToolResult.success(
                    "Rolled back " + checkpoint.relativePath()
                            + " from checkpoint " + checkpoint.id()
            );
        } catch (IOException e) {
            return ToolResult.failure(e);
        }
    }
}
