package dev.pironi.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.pironi.safety.CheckpointManager;
import dev.pironi.safety.Workspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplyPatchToolTest {
    @TempDir
    Path workspaceRoot;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ApplyPatchTool patchTool;
    private RollbackCheckpointTool rollbackTool;

    @BeforeEach
    void setUp() throws Exception {
        Workspace workspace = new Workspace(workspaceRoot);
        CheckpointManager checkpoints = new CheckpointManager(workspace);
        patchTool = new ApplyPatchTool(workspace, checkpoints);
        rollbackTool = new RollbackCheckpointTool(checkpoints);
    }

    @Test
    void appliesExactReplacementAndCanRollback() throws Exception {
        Path file = Files.writeString(workspaceRoot.resolve("Example.java"), "before\nbug\n");
        var arguments = objectMapper.readTree("""
                {"path":"Example.java","oldText":"bug","newText":"fixed"}
                """);

        assertTrue(patchTool.approvalPreview(arguments).contains("-bug\n+fixed"));
        ToolResult applied = patchTool.execute(arguments);

        assertTrue(applied.success());
        assertEquals("before\nfixed\n", Files.readString(file));
        assertTrue(rollbackTool.execute(objectMapper.createObjectNode()).success());
        assertEquals("before\nbug\n", Files.readString(file));
    }

    @Test
    void patchesAFileWithWindowsLineEndings() throws Exception {
        // Git for Windows checks files out with CRLF by default while the model writes LF, so an
        // exact match failed on text that was plainly in the file and the tool reported it as
        // missing. This is the normal state of a Windows checkout, not an edge case.
        Path file = Files.writeString(
                workspaceRoot.resolve("Windows.java"), "before\r\nbug here\r\nafter\r\n");

        ToolResult applied = patchTool.execute(objectMapper.readTree("""
                {"path":"Windows.java","oldText":"before\\nbug here","newText":"before\\nfixed"}
                """));

        assertTrue(applied.success(), applied.output());
        // The file keeps the endings it had; the patch must not convert it to LF.
        assertEquals("before\r\nfixed\r\nafter\r\n", Files.readString(file));
    }

    @Test
    void ambiguityIsStillDetectedAcrossLineEndings() throws Exception {
        Path file = Files.writeString(
                workspaceRoot.resolve("twice.txt"), "a\r\nb\r\nc\r\na\r\nb\r\n");

        ToolResult result = patchTool.execute(objectMapper.readTree("""
                {"path":"twice.txt","oldText":"a\\nb","newText":"x"}
                """));

        assertFalse(result.success(), result.output());
        assertEquals("a\r\nb\r\nc\r\na\r\nb\r\n", Files.readString(file));
    }

    @Test
    void refusesAmbiguousReplacementWithoutChangingFile() throws Exception {
        Path file = Files.writeString(workspaceRoot.resolve("duplicate.txt"), "same same");

        ToolResult result = patchTool.execute(objectMapper.readTree("""
                {"path":"duplicate.txt","oldText":"same","newText":"other"}
                """));

        assertFalse(result.success());
        assertEquals("same same", Files.readString(file));
    }

    @Test
    void createsNewFileAndRollbackRemovesIt() throws Exception {
        ToolResult result = patchTool.execute(objectMapper.readTree("""
                {"path":"new.txt","oldText":"","newText":"created"}
                """));

        assertTrue(result.success());
        assertEquals("created", Files.readString(workspaceRoot.resolve("new.txt")));
        assertTrue(rollbackTool.execute(objectMapper.createObjectNode()).success());
        assertFalse(Files.exists(workspaceRoot.resolve("new.txt")));
    }

    @Test
    void refusesOversizedFile() throws Exception {
        Path file = Files.writeString(
                workspaceRoot.resolve("large.txt"),
                "x".repeat(1_048_577)
        );

        ToolResult result = patchTool.execute(objectMapper.readTree("""
                {"path":"large.txt","oldText":"x","newText":"y"}
                """));

        assertFalse(result.success());
        assertEquals(1_048_577, Files.size(file));
    }
}
