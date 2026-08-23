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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void reportsTheShapeOfATreeTooBigToList() throws Exception {
        String longName = "a".repeat(120);
        for (int index = 0; index < 600; index++) {
            Files.writeString(workspaceRoot.resolve(longName + index + ".txt"), "x");
        }
        Files.createDirectory(workspaceRoot.resolve("logs"));
        for (int index = 0; index < 50; index++) {
            Files.writeString(workspaceRoot.resolve("logs").resolve("entry" + index + ".xmlgz"), "y");
        }

        ToolResult result = new ListFilesTool(new Workspace(workspaceRoot), 500)
                .execute(OBJECT_MAPPER.readTree("{\"path\":\".\"}"));

        assertEquals(true, result.success());
        String output = result.output();
        assertEquals(true, output.startsWith("650 files, "), output);
        assertEquals(true, output.contains(".txt 600"), output);
        assertEquals(true, output.contains(".xmlgz 50"), output);
        assertEquals(true, output.contains("logs 50"), output);
        assertEquals(true, output.contains("this is a profile"), output);
        // the whole point: the shape costs a fraction of the truncated listing
        assertEquals(true, output.length() < 2_000, "profile length " + output.length());
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

    @Test
    void oneDirectoryThisAccountCannotOpenDoesNotLoseTheListing() throws Exception {
        Files.writeString(workspaceRoot.resolve("keep.txt"), "");
        Path locked = Files.createDirectories(workspaceRoot.resolve("locked"));
        Files.writeString(locked.resolve("inside.txt"), "");
        if (!denyAccess(locked)) {
            org.junit.jupiter.api.Assumptions.abort("this account cannot stage an unreadable dir");
        }
        try {
            // The premise, asserted rather than assumed: the directory really will not open.
            try (var stream = Files.newDirectoryStream(locked)) {
                stream.iterator();
                org.junit.jupiter.api.Assumptions.abort("the deny did not take on this filesystem");
            } catch (java.io.IOException expected) {
                // good - this is the case the walk used to die on
            }

            ToolResult result = new ListFilesTool(new Workspace(workspaceRoot), 100)
                    .execute(OBJECT_MAPPER.createObjectNode().put("path", "."));

            // Files.walk abandoned the whole traversal here, so a user listing their own home on
            // Windows got nothing at all - one INetCache directory took the rest down with it.
            assertEquals(true, result.success(), result.output());
            assertEquals(true, result.output().contains("keep.txt"), result.output());
        } finally {
            restoreAccess(locked);
        }
    }

    private static boolean denyAccess(Path directory) throws Exception {
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            return icacls("/deny", System.getProperty("user.name") + ":(OI)(CI)(RX)", directory);
        }
        try {
            Files.setPosixFilePermissions(directory, Set.of());
            return true;
        } catch (UnsupportedOperationException | java.io.IOException e) {
            return false;
        }
    }

    private static void restoreAccess(Path directory) throws Exception {
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            icacls("/remove:d", System.getProperty("user.name"), directory);
            return;
        }
        try {
            Files.setPosixFilePermissions(directory,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException | java.io.IOException ignored) {
            // nothing left to restore; the temp directory cleanup will report it if it matters
        }
    }

    private static boolean icacls(String flag, String argument, Path directory) throws Exception {
        Process process = new ProcessBuilder(
                "icacls", directory.toString(), flag, argument)
                .redirectErrorStream(true).start();
        return process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)
                && process.exitValue() == 0;
    }

    @Test
    void doesNotResolveEveryVisitedFileToAnswerAQuestionAboutOne() throws Exception {
        // Listing %LOCALAPPDATA%\Packages took 18 seconds because the hidden-path check called
        // toRealPath on every file it walked. The names almost never match, and a name comparison
        // is free.
        Path root = Files.createDirectory(workspaceRoot.resolve("many"));
        for (int i = 0; i < 300; i++) {
            Files.writeString(root.resolve("file-" + i + ".txt"), "x");
        }
        Path hidden = Files.writeString(root.resolve("trace.jsonl"), "secret");
        ListFilesTool tool = new ListFilesTool(
                new Workspace(root), 1_000, List.of(root), Set.of(hidden));

        ToolResult result = tool.execute(
                new ObjectMapper().createObjectNode().put("path", root.toString()));

        assertTrue(result.success(), result.output());
        assertFalse(result.output().contains("trace.jsonl"), "the hidden path leaked");
        assertTrue(result.output().contains("file-299.txt"), result.output());
    }
}
