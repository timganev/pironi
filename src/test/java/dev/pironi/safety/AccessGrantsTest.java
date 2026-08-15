package dev.pironi.safety;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.pironi.tool.InspectFileTool;
import dev.pironi.tool.ReadFileTool;
import dev.pironi.tool.Tool;
import dev.pironi.tool.ToolRegistry;
import dev.pironi.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessGrantsTest {
    @TempDir Path workspaceRoot;
    @TempDir Path outside;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void readingOutsideRootsFailsUntilTheDirectoryIsGranted() throws Exception {
        Files.writeString(outside.resolve("plan.md"), "Бюджет: 128000 EUR");
        AccessGrants grants = new AccessGrants();
        ReadFileTool tool = new ReadFileTool(
                new Workspace(workspaceRoot), 32_000, List.of(workspaceRoot), Set.of());
        tool.useGrants(grants);
        com.fasterxml.jackson.databind.node.ObjectNode request = mapper.createObjectNode();
        request.put("path", outside.resolve("plan.md").toAbsolutePath().toString());

        ToolResult before = tool.execute(request);
        assertFalse(before.success(), "must refuse before the grant");

        grants.grantRoot(outside);
        ToolResult after = tool.execute(request);
        assertTrue(after.success(), after.output());
        assertTrue(after.output().contains("128000"), after.output());
    }

    @Test void revokingADirectoryClosesAccessAgain() throws Exception {
        Files.writeString(outside.resolve("plan.md"), "secret");
        AccessGrants grants = new AccessGrants();
        InspectFileTool tool = new InspectFileTool(new Workspace(workspaceRoot), List.of(workspaceRoot));
        tool.useGrants(grants);
        com.fasterxml.jackson.databind.node.ObjectNode request = mapper.createObjectNode();
        request.put("path", outside.resolve("plan.md").toAbsolutePath().toString());

        Path granted = grants.grantRoot(outside);
        assertTrue(tool.execute(request).success());
        assertTrue(grants.revokeRoot(granted), "revoke must report success");
        assertFalse(tool.execute(request).success(), "must refuse after revoke");
    }

    @Test void grantingANonDirectoryIsRejected() throws Exception {
        Path file = Files.writeString(outside.resolve("not-a-dir.txt"), "x");
        AccessGrants grants = new AccessGrants();
        assertThrows(java.io.IOException.class, () -> grants.grantRoot(file));
    }

    @Test void blockedToolStaysImplementedAndCanBeReEnabled() throws Exception {
        AccessGrants grants = new AccessGrants();
        Tool readFile = new ReadFileTool(new Workspace(workspaceRoot), 1_000);
        ToolRegistry registry = new ToolRegistry(List.of(readFile), grants);

        grants.disableTool("read_file");
        assertTrue(registry.find("read_file").isEmpty(), "blocked tool must not be callable");
        assertTrue(registry.isBlocked("read_file"), "blocked is not the same as absent");
        assertEquals(1, registry.allImplemented().size(),
                "a blocked tool is still implemented and must be reportable as such");
        assertTrue(registry.all().isEmpty(), "blocked tool is not exposed to the model");

        assertTrue(grants.enableTool("read_file"));
        assertTrue(registry.find("read_file").isPresent(), "must be callable after /allow-tool");
        assertFalse(registry.isBlocked("read_file"));
    }

    @Test void readOnlyModeAndPolicyBlockAreIndependent() throws Exception {
        AccessGrants grants = new AccessGrants();
        ToolRegistry registry = new ToolRegistry(
                List.of(new ReadFileTool(new Workspace(workspaceRoot), 1_000)), grants);
        registry.setReadOnly(true);
        // read_file is non-mutating, so read-only leaves it exposed.
        assertTrue(registry.find("read_file").isPresent());
        grants.disableTool("read_file");
        assertTrue(registry.find("read_file").isEmpty(), "policy block applies on top of read-only");
    }
}
