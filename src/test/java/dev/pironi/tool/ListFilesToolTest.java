package dev.pironi.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.pironi.safety.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ListFilesToolTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path workspaceRoot;

    @Test
    void excludesGeneratedAndPrivateDirectories() throws Exception {
        Files.writeString(workspaceRoot.resolve("pom.xml"), "");
        writeInDirectory("target", "Generated.class");
        writeInDirectory(".git", "config");
        writeInDirectory(".pironi", "trace.jsonl");
        writeInDirectory(".idea", "workspace.xml");
        writeInDirectory("node_modules", "package.js");

        ToolResult result = new ListFilesTool(new Workspace(workspaceRoot), 100)
                .execute(OBJECT_MAPPER.readTree("{\"path\":\".\"}"));

        assertEquals(true, result.success());
        assertEquals("pom.xml", result.output());
    }

    @Test
    void excludesExplicitTracePathOutsidePrivateDirectory() throws Exception {
        Path visible = Files.writeString(workspaceRoot.resolve("visible.txt"), "ok");
        Path trace = Files.writeString(workspaceRoot.resolve("trace.jsonl"), "private");

        ToolResult result = new ListFilesTool(
                new Workspace(workspaceRoot), 100, Set.of(trace)
        ).execute(OBJECT_MAPPER.readTree("{\"path\":\".\"}"));

        assertEquals(visible.getFileName().toString(), result.output());
    }

    @Test
    void listsAbsoluteDirectoryBelowConfiguredSearchRoot() throws Exception {
        Path externalRoot = Files.createDirectory(workspaceRoot.resolveSibling(
                workspaceRoot.getFileName() + "-external"
        ));
        Path downloads = Files.createDirectory(externalRoot.resolve("Downloads"));
        Path document = Files.writeString(downloads.resolve("report.txt"), "content");
        ListFilesTool tool = new ListFilesTool(
                new Workspace(workspaceRoot), 100, List.of(externalRoot), Set.of()
        );

        ToolResult allowed = tool.execute(OBJECT_MAPPER.createObjectNode()
                .put("path", downloads.toString()));
        ToolResult rejected = tool.execute(OBJECT_MAPPER.createObjectNode()
                .put("path", externalRoot.getParent().toString()));

        assertEquals(true, allowed.success());
        assertEquals(document.toRealPath().toString(), allowed.output());
        assertEquals(false, rejected.success());
    }

    private void writeInDirectory(String directory, String file) throws Exception {
        Path path = Files.createDirectories(workspaceRoot.resolve(directory)).resolve(file);
        Files.writeString(path, "");
    }
}
