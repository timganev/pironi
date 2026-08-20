package dev.pironi.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.pironi.safety.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WriteFileToolTest {
    @TempDir
    Path workspaceRoot;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void replacesFileAndLeavesNoTemporaryFile() throws Exception {
        Files.writeString(workspaceRoot.resolve("file.txt"), "old");
        WriteFileTool tool = new WriteFileTool(new Workspace(workspaceRoot));

        ToolResult result = tool.execute(objectMapper.readTree("""
                {"path":"file.txt","content":"new content"}
                """));

        assertTrue(result.success());
        assertEquals("new content", Files.readString(workspaceRoot.resolve("file.txt")));
        try (var files = Files.list(workspaceRoot)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().startsWith(".pironi-")));
        }
    }

    @Test
    void snapshotsWhatItIsAboutToOverwrite() throws Exception {
        // apply_patch and move_file both checkpoint; this one did not, so the safe tool could
        // be undone and the destructive one could not.
        Files.writeString(workspaceRoot.resolve("precious.txt"), "original");
        var checkpoints = new dev.pironi.safety.CheckpointManager(new Workspace(workspaceRoot));
        WriteFileTool tool = new WriteFileTool(new Workspace(workspaceRoot), checkpoints);

        ToolResult result = tool.execute(objectMapper.readTree("""
                {"path":"precious.txt","content":"replaced"}
                """));

        assertTrue(result.success());
        assertTrue(result.output().contains("checkpoint="), result.output());
    }

    @Test
    void aNewFileNeedsNoSnapshot() throws Exception {
        var checkpoints = new dev.pironi.safety.CheckpointManager(new Workspace(workspaceRoot));
        WriteFileTool tool = new WriteFileTool(new Workspace(workspaceRoot), checkpoints);

        ToolResult result = tool.execute(objectMapper.readTree("""
                {"path":"fresh.txt","content":"new"}
                """));

        assertTrue(result.success());
        assertFalse(result.output().contains("checkpoint="), result.output());
    }

    @Test
    void pointsAtApplyPatchWhenAFileIsOverwritten() throws Exception {
        Files.writeString(workspaceRoot.resolve("script.sh"), "old");
        WriteFileTool tool = new WriteFileTool(new Workspace(workspaceRoot));

        ToolResult result = tool.execute(objectMapper.readTree("""
                {"path":"script.sh","content":"new"}
                """));

        assertTrue(result.success());
        assertTrue(result.output().contains("apply_patch"), result.output());
    }

    @Test
    void saysNothingAboutApplyPatchOnAFirstWrite() throws Exception {
        WriteFileTool tool = new WriteFileTool(new Workspace(workspaceRoot));

        ToolResult result = tool.execute(objectMapper.readTree("""
                {"path":"fresh.sh","content":"new"}
                """));

        assertTrue(result.success());
        assertFalse(result.output().contains("apply_patch"), result.output());
    }

    @Test
    void refusesWriteOutsideWorkspace() throws Exception {
        WriteFileTool tool = new WriteFileTool(new Workspace(workspaceRoot));

        ToolResult result = tool.execute(objectMapper.readTree("""
                {"path":"../outside.txt","content":"no"}
                """));

        assertFalse(result.success());
        assertFalse(Files.exists(workspaceRoot.getParent().resolve("outside.txt")));
    }

    @Test
    void createsMissingParentDirectories() throws Exception {
        WriteFileTool tool = new WriteFileTool(new Workspace(workspaceRoot));

        ToolResult result = tool.execute(objectMapper.readTree("""
                {"path":"reports/daily/result.md","content":"ready"}
                """));

        assertTrue(result.success());
        assertEquals("ready", Files.readString(
                workspaceRoot.resolve("reports/daily/result.md")
        ));
    }
}
