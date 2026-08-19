package dev.pironi.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceCommandTest {
    @TempDir
    Path temporaryDirectory;

    private DefaultShellCommands commands(dev.pironi.safety.Workspace workspace,
            AtomicReference<Path> moved) {
        return commands(workspace, moved::set);
    }

    private DefaultShellCommands commands(dev.pironi.safety.Workspace workspace,
            java.util.function.Consumer<Path> onChange) {
        DefaultShellCommands shell = new DefaultShellCommands(null, null, null, null, null, null);
        shell.useWorkspace(workspace, onChange);
        return shell;
    }

    @Test
    void movesTheSandboxAndReportsTheChangeOnce() throws Exception {
        Path first = Files.createDirectory(temporaryDirectory.resolve("project"));
        Path second = Files.createDirectory(temporaryDirectory.resolve("repo"));
        var workspace = new dev.pironi.safety.Workspace(first);
        AtomicReference<Path> moved = new AtomicReference<>();
        DefaultShellCommands shell = commands(workspace, moved);

        assertTrue(shell.workspace("").contains(first.toRealPath().toString()));

        String switched = shell.workspace(second.toString());

        assertTrue(switched.startsWith("Workspace switched"), switched);
        assertEquals(second.toRealPath(), workspace.root());
        assertEquals(second.toRealPath(), moved.get());

        // Switching to where it already is must not tell the rest of the session to move.
        moved.set(null);
        assertTrue(shell.workspace(second.toString()).startsWith("Already the workspace"));
        assertEquals(null, moved.get());
    }

    @Test
    void aChildSpawnedAfterAMoveReadsTheDirectoryTheSessionIsIn() throws Exception {
        // Sub-agents get their own read-only tools, whose roots are built once at startup. A
        // child spawned after /workspace was reading the directory the session had left, which
        // is how an audit of the current project came back saying it could not read it.
        Path first = Files.createDirectory(temporaryDirectory.resolve("start"));
        Path second = Files.createDirectory(temporaryDirectory.resolve("moved"));
        Files.writeString(second.resolve("Subject.java"), "class Subject {}\n");
        var workspace = new dev.pironi.safety.Workspace(first);
        var grants = new dev.pironi.safety.AccessGrants();
        var childRead = new dev.pironi.tool.ReadFileTool(
                workspace, 32_000, java.util.List.of(), java.util.Set.of());
        childRead.useGrants(grants);
        var arguments = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                "{\"path\":\"" + second.resolve("Subject.java").toRealPath() + "\"}");

        assertTrue(childRead.execute(arguments).output().contains("outside"),
                "not readable before the move");

        DefaultShellCommands shell = commands(workspace, moved -> {
            try {
                grants.grantRoot(moved);
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
        });
        shell.workspace(second.toString());

        assertTrue(childRead.execute(arguments).success(), "readable after the move");
    }

    @Test
    void reportsAPathThatCannotBecomeAWorkspace() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("root"));
        Path file = Files.writeString(temporaryDirectory.resolve("plain.txt"), "x");
        var workspace = new dev.pironi.safety.Workspace(root);
        DefaultShellCommands shell = commands(workspace, new AtomicReference<>());

        String refused = shell.workspace(file.toString());

        assertTrue(refused.startsWith("Could not switch workspace"), refused);
        assertEquals(root.toRealPath(), workspace.root());
    }

    @Test
    void expandsHomeSoThatATypedTildeIsNotANewDirectory() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("root"));
        var workspace = new dev.pironi.safety.Workspace(root);
        DefaultShellCommands shell = commands(workspace, new AtomicReference<>());

        String switched = shell.workspace("~");

        assertTrue(switched.startsWith("Workspace switched"), switched);
        assertEquals(Path.of(System.getProperty("user.home")).toRealPath(), workspace.root());
    }
}
