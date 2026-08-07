package dev.pironi.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.pironi.safety.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ReadFileToolTest {
    @TempDir Path root;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void readsAbsoluteFileFromConfiguredReadOnlyRoot() throws Exception {
        Path workspaceRoot = Files.createDirectory(root.resolve("workspace"));
        Path searchRoot = Files.createDirectory(root.resolve("search"));
        Path note = Files.writeString(searchRoot.resolve("note.md"), "asset-42");
        ReadFileTool tool = new ReadFileTool(
                new Workspace(workspaceRoot), 1_000, List.of(searchRoot), Set.of()
        );

        ToolResult result = tool.execute(
                mapper.createObjectNode().put("path", note.toString())
        );

        assertTrue(result.success());
        assertTrue(result.output().contains("asset-42"));
    }

    @Test
    void rejectsOutsideAndHiddenFiles() throws Exception {
        Path workspaceRoot = Files.createDirectory(root.resolve("workspace"));
        Path hidden = Files.writeString(workspaceRoot.resolve("trace.jsonl"), "private");
        Path outside = Files.writeString(root.resolve("outside.txt"), "outside");
        ReadFileTool tool = new ReadFileTool(
                new Workspace(workspaceRoot), 1_000, List.of(workspaceRoot), Set.of(hidden)
        );

        ToolResult hiddenResult = tool.execute(
                mapper.createObjectNode().put("path", "trace.jsonl")
        );
        ToolResult outsideResult = tool.execute(
                mapper.createObjectNode().put("path", outside.toString())
        );

        assertFalse(hiddenResult.success());
        assertFalse(outsideResult.success());
    }

    @Test
    void rejectsSymlinkThatResolvesOutsideReadRoots() throws Exception {
        Path workspaceRoot = Files.createDirectory(root.resolve("workspace"));
        Path outside = Files.writeString(root.resolve("canary.txt"), "do-not-leak");
        Path link = workspaceRoot.resolve("linked.txt");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            assumeTrue(false, "Symbolic links are unavailable: " + e.getMessage());
        }
        ReadFileTool tool = new ReadFileTool(new Workspace(workspaceRoot), 1_000);

        ToolResult result = tool.execute(
                mapper.createObjectNode().put("path", "linked.txt")
        );

        assertFalse(result.success());
        assertFalse(result.output().contains("do-not-leak"));
    }
}
