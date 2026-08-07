package dev.pironi.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.pironi.safety.CheckpointManager;
import dev.pironi.safety.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MoveFileToolTest {
    @TempDir Path root;

    @Test
    void movesWithoutOverwriteAndPreservesContent() throws Exception {
        Files.writeString(root.resolve("source.txt"), "Здравей");
        Workspace workspace = new Workspace(root);
        MoveFileTool tool = new MoveFileTool(workspace, new CheckpointManager(workspace));
        var mapper = new ObjectMapper();

        ToolResult moved = tool.execute(mapper.createObjectNode()
                .put("source", "source.txt").put("destination", "archive/result.txt"));

        assertTrue(moved.success());
        assertFalse(Files.exists(root.resolve("source.txt")));
        assertTrue(Files.readString(root.resolve("archive/result.txt")).equals("Здравей"));
        assertTrue(moved.output().contains("sha256="));
    }

    @Test
    void rejectsExistingDestinationAndTraversal() throws Exception {
        Files.writeString(root.resolve("one.txt"), "one");
        Files.writeString(root.resolve("two.txt"), "two");
        Workspace workspace = new Workspace(root);
        MoveFileTool tool = new MoveFileTool(workspace, new CheckpointManager(workspace));
        var mapper = new ObjectMapper();

        assertFalse(tool.execute(mapper.createObjectNode()
                .put("source", "one.txt").put("destination", "two.txt")).success());
        assertFalse(tool.execute(mapper.createObjectNode()
                .put("source", "one.txt").put("destination", "../escape.txt")).success());
    }
}
