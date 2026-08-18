package dev.pironi.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.pironi.safety.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class InspectFileToolTest {
    @TempDir Path root;
    @Test void classifiesBinaryAndCountsWithoutReturningContents() throws Exception {
        Files.write(root.resolve("binary.bin"), new byte[]{0, 1, 2, (byte) 255, 65});
        ToolResult result = new InspectFileTool(new Workspace(root), List.of(root)).execute(
                new ObjectMapper().createObjectNode().put("path", "binary.bin"));
        assertTrue(result.success(), result.output());
        assertTrue(result.output().contains("sizeBytes=5"));
        assertTrue(result.output().contains("classification=binary-or-non-utf8"));
        assertTrue(result.output().contains("sha256="));
    }
    @Test void inspectsAbsoluteDescendantOfReadRoot() throws Exception {
        Path child = Files.createDirectories(root.resolve("child"));
        Path file = Files.writeString(child.resolve("large.txt"), "one\r\ntwo\n");
        ToolResult result = new InspectFileTool(new Workspace(child), List.of(root)).execute(
                new ObjectMapper().createObjectNode().put("path", file.toString()));
        assertTrue(result.success(), result.output());
        assertTrue(result.output().contains("classification=utf-8-text"));
        assertTrue(result.output().contains("lfCount=2"));
        assertTrue(result.output().contains("crlfCount=1"));
    }

    @Test void namesTheContainerFormatInsteadOfCallingItBinary() throws Exception {
        Path gz = root.resolve("log.xmlgz");
        try (var out = new java.util.zip.GZIPOutputStream(Files.newOutputStream(gz))) {
            out.write("<response><Subject>hi</Subject></response>".getBytes());
        }
        ToolResult result = new InspectFileTool(new Workspace(root), List.of(root)).execute(
                new ObjectMapper().createObjectNode().put("path", "log.xmlgz"));
        assertTrue(result.success(), result.output());
        assertTrue(result.output().contains("classification=gzip-compressed"), result.output());
        assertTrue(result.output().contains("gunzip -c"), result.output());
    }
}
