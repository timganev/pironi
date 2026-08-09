package dev.pironi.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FindFilesToolTest {
    @TempDir Path root;

    @Test
    void searchesOnlyAllowedRootsByNameAndContent() throws Exception {
        Files.writeString(root.resolve("note.md"), "marker-7319");
        Files.writeString(root.resolve("other.txt"), "marker-7319");
        FindFilesTool tool = new FindFilesTool(List.of(root));
        var mapper = new ObjectMapper();

        ToolResult found = tool.execute(mapper.createObjectNode()
                .put("root", root.toString()).put("name", "*.md").put("contains", "marker-7319"));
        ToolResult rejected = tool.execute(mapper.createObjectNode().put("root", root.getParent().toString()));

        assertTrue(found.success());
        assertTrue(found.output().contains("note.md"));
        assertFalse(found.output().contains("other.txt"));
        assertFalse(rejected.success());
    }

    @Test
    void excludesExplicitHiddenPath() throws Exception {
        Path visible = Files.writeString(root.resolve("visible.txt"), "ok");
        Path hidden = Files.writeString(root.resolve("trace.jsonl"), "secret");
        FindFilesTool tool = new FindFilesTool(List.of(root), Set.of(hidden));

        ToolResult result = tool.execute(new ObjectMapper().createObjectNode()
                .put("root", root.toString()).put("name", "*"));

        assertTrue(result.success(), result.output());
        assertTrue(result.output().contains(visible.toRealPath().toString()));
        assertFalse(result.output().contains(hidden.toRealPath().toString()));
    }

    @Test
    void skipsDirectoryLinksWithoutLosingNormalMatches() throws Exception {
        Path documents = Files.createDirectories(root.resolve("Documents"));
        Path expected = Files.writeString(documents.resolve("marker.txt"), "junction-safe-18d2");
        Path loop = root.resolve("Application Data");
        try {
            Files.createSymbolicLink(loop, root);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException e) {
            org.junit.jupiter.api.Assumptions.abort("Directory links are not available: " + e.getMessage());
        }

        ToolResult result = new FindFilesTool(List.of(root)).execute(
                new ObjectMapper().createObjectNode()
                        .put("root", root.toString())
                        .put("contains", "junction-safe-18d2")
        );

        assertTrue(result.success(), result.output());
        assertTrue(result.output().contains(expected.toRealPath().toString()));
        assertFalse(result.output().contains("Application Data"));
    }
}
