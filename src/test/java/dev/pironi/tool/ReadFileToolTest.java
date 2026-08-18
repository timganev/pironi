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
import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void readsBoundedHeadRangeWithoutChangingDefaultReadBehavior() throws Exception {
        Path workspaceRoot = Files.createDirectory(root.resolve("workspace"));
        Files.writeString(workspaceRoot.resolve("rows.csv"), "one\r\ntwo\r\nтри\r\nfour\r\n");
        ReadFileTool tool = new ReadFileTool(new Workspace(workspaceRoot), 1_000);

        ToolResult defaultResult = tool.execute(
                mapper.createObjectNode().put("path", "rows.csv")
        );
        ToolResult rangeResult = tool.execute(mapper.createObjectNode()
                .put("path", "rows.csv").put("startLine", 2).put("lineCount", 2));

        assertEquals("one\r\ntwo\r\nтри\r\nfour\r\n", defaultResult.output());
        assertEquals("two\nтри", rangeResult.output());
    }

    @Test
    void readsTailWithBoundedMemoryAndUtf8Content() throws Exception {
        Path workspaceRoot = Files.createDirectory(root.resolve("workspace"));
        Files.writeString(workspaceRoot.resolve("large.csv"),
                "row-1\nrow-2\nrow-3\nред-4\nrow-5\n");
        ReadFileTool tool = new ReadFileTool(new Workspace(workspaceRoot), 1_000);

        ToolResult result = tool.execute(mapper.createObjectNode()
                .put("path", "large.csv").put("tailLines", 2));

        assertTrue(result.success());
        assertEquals("ред-4\nrow-5", result.output());
    }

    @Test
    void rejectsUnboundedOrAmbiguousRangeArguments() throws Exception {
        Path workspaceRoot = Files.createDirectory(root.resolve("workspace"));
        Files.writeString(workspaceRoot.resolve("rows.txt"), "one\ntwo\n");
        ReadFileTool tool = new ReadFileTool(new Workspace(workspaceRoot), 1_000);

        ToolResult ambiguous = tool.execute(mapper.createObjectNode()
                .put("path", "rows.txt").put("startLine", 1).put("tailLines", 1));
        ToolResult excessive = tool.execute(mapper.createObjectNode()
                .put("path", "rows.txt").put("lineCount", 10_001));

        assertFalse(ambiguous.success());
        assertTrue(ambiguous.output().contains("cannot be combined"));
        assertFalse(excessive.success());
        assertTrue(excessive.output().contains("between 1 and 10000"));
    }

    @Test
    void rangeOutputStillHonorsCharacterLimit() throws Exception {
        Path workspaceRoot = Files.createDirectory(root.resolve("workspace"));
        Files.writeString(workspaceRoot.resolve("rows.txt"), "12345\n67890\n");
        ReadFileTool tool = new ReadFileTool(new Workspace(workspaceRoot), 7);

        ToolResult result = tool.execute(mapper.createObjectNode()
                .put("path", "rows.txt").put("tailLines", 2));

        assertTrue(result.success());
        assertTrue(result.output().startsWith("12345\n6"));
        assertTrue(result.output().contains("[truncated after 7 characters"));
        // the cut now points at how to get the rest instead of just stopping
        assertTrue(result.output().contains("startLine/lineCount"), result.output());
    }
}
