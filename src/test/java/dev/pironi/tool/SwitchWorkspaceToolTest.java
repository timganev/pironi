package dev.pironi.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.pironi.safety.ApprovalDecision;
import dev.pironi.safety.ApprovalMode;
import dev.pironi.safety.ConsoleApprovalPolicy;
import dev.pironi.safety.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SwitchWorkspaceToolTest {
    @TempDir
    Path temporaryDirectory;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private com.fasterxml.jackson.databind.JsonNode arguments(String path) throws Exception {
        return objectMapper.readTree(objectMapper.writeValueAsString(
                java.util.Map.of("path", path)));
    }

    @Test
    void movesTheWorkspaceAndTellsTheSessionAboutIt() throws Exception {
        Path first = Files.createDirectory(temporaryDirectory.resolve("start"));
        Path second = Files.createDirectory(temporaryDirectory.resolve("target"));
        Workspace workspace = new Workspace(first);
        SwitchWorkspaceTool tool = new SwitchWorkspaceTool(workspace);
        AtomicReference<Path> moved = new AtomicReference<>();
        tool.onSwitch(moved::set);

        ToolResult result = tool.execute(arguments(second.toString()));

        assertTrue(result.success(), result.output());
        assertEquals(second.toRealPath(), workspace.root());
        assertEquals(second.toRealPath(), moved.get());
    }

    @Test
    void neverRunsWithoutTheUserSayingYes() throws Exception {
        // The whole point of the tool is that the human decides; auto approval must not
        // silently move the sandbox on a document's say-so.
        Path root = Files.createDirectory(temporaryDirectory.resolve("root"));
        Path other = Files.createDirectory(temporaryDirectory.resolve("other"));
        Workspace workspace = new Workspace(root);
        SwitchWorkspaceTool tool = new SwitchWorkspaceTool(workspace);
        var arguments = arguments(other.toString());

        assertTrue(tool.requiresExplicitApproval(arguments));
        assertTrue(tool.approvalPreview(arguments).startsWith("/workspace " + other));

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ConsoleApprovalPolicy refusing = new ConsoleApprovalPolicy(
                ApprovalMode.AUTO,
                new BufferedReader(new StringReader("n\n")),
                new PrintStream(bytes, true, StandardCharsets.UTF_8)
        );

        assertEquals(ApprovalDecision.DENY, refusing.decide(tool, arguments));
        assertEquals(root.toRealPath(), workspace.root(), "a refused move must change nothing");
        assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("/workspace " + other),
                "the prompt must show which directory is being taken");
    }

    @Test
    void refusesAPathThatIsNotAnExistingDirectory() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("only"));
        Path file = Files.writeString(temporaryDirectory.resolve("plain.txt"), "x");
        Workspace workspace = new Workspace(root);
        SwitchWorkspaceTool tool = new SwitchWorkspaceTool(workspace);

        ToolResult missing = tool.execute(arguments(
                temporaryDirectory.resolve("nowhere").toString()));
        ToolResult notDirectory = tool.execute(arguments(file.toString()));

        assertFalse(missing.success());
        assertTrue(missing.output().contains("No such directory"), missing.output());
        assertFalse(notDirectory.success());
        assertEquals(root.toRealPath(), workspace.root());
    }
}
