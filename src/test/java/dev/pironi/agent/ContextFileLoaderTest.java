package dev.pironi.agent;

import dev.pironi.model.ProviderType;
import dev.pironi.safety.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ContextFileLoaderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void cloudAutoExcludesPersonalContextButIncludesProjectInstructions() throws Exception {
        Path workspaceRoot = Files.createDirectory(temporaryDirectory.resolve("workspace"));
        Files.writeString(workspaceRoot.resolve("CLAUDE.md"), "project rules");
        Path pironiHome = Files.createDirectory(temporaryDirectory.resolve("pironi-home"));
        Files.writeString(pironiHome.resolve("SOUL.md"), "private soul");
        Files.writeString(pironiHome.resolve("USER.md"), "private user");

        AgentContext context = ContextFileLoader.load(
                new Workspace(workspaceRoot),
                ProviderType.OPENAI_COMPATIBLE,
                PersonalContextMode.AUTO,
                pironiHome
        );

        assertEquals("", context.soul());
        assertEquals("", context.userProfile());
        assertEquals("project rules", context.projectInstructions());
    }

    @Test
    void ollamaAutoIncludesPersonalContext() throws Exception {
        Path workspaceRoot = Files.createDirectory(temporaryDirectory.resolve("workspace"));
        Path pironiHome = Files.createDirectory(temporaryDirectory.resolve("pironi-home"));
        Files.writeString(pironiHome.resolve("SOUL.md"), "local soul");
        Files.writeString(pironiHome.resolve("USER.md"), "local user");

        AgentContext context = ContextFileLoader.load(
                new Workspace(workspaceRoot),
                ProviderType.OLLAMA,
                PersonalContextMode.AUTO,
                pironiHome
        );

        assertEquals("local soul", context.soul());
        assertEquals("local user", context.userProfile());
    }

    @Test
    void projectRuntimeMarkerExcludesLongTermProjectState() throws Exception {
        Path workspaceRoot = Files.createDirectory(temporaryDirectory.resolve("workspace-marker"));
        Files.writeString(
                workspaceRoot.resolve("CLAUDE.md"),
                "runtime rules\n<!-- pironi-runtime-context-end -->\nlong-term progress"
        );
        Path pironiHome = Files.createDirectory(temporaryDirectory.resolve("empty-pironi-home"));

        AgentContext context = ContextFileLoader.load(
                new Workspace(workspaceRoot),
                ProviderType.OLLAMA,
                PersonalContextMode.DENY,
                pironiHome
        );

        assertEquals("runtime rules", context.projectInstructions());
    }

    @Test
    void projectLimitAppliesOnlyBeforeRuntimeMarker() throws Exception {
        Path workspaceRoot = Files.createDirectory(temporaryDirectory.resolve("large-project-state"));
        Files.writeString(
                workspaceRoot.resolve("CLAUDE.md"),
                "runtime rules\n<!-- pironi-runtime-context-end -->\n" + "x".repeat(30_000)
        );
        Path pironiHome = Files.createDirectory(temporaryDirectory.resolve("large-state-home"));

        AgentContext context = ContextFileLoader.load(
                new Workspace(workspaceRoot),
                ProviderType.OLLAMA,
                PersonalContextMode.DENY,
                pironiHome
        );

        assertEquals("runtime rules", context.projectInstructions());
    }

    @Test
    void deepSeekAutoDoesNotLoadPersonalContext() throws Exception {
        Path workspaceRoot = Files.createDirectory(temporaryDirectory.resolve("deepseek-workspace"));
        Path pironiHome = Files.createDirectory(temporaryDirectory.resolve("deepseek-home"));
        Files.writeString(pironiHome.resolve("SOUL.md"), "private soul");
        Files.writeString(pironiHome.resolve("USER.md"), "private user");

        AgentContext context = ContextFileLoader.load(
                new Workspace(workspaceRoot),
                ProviderType.DEEPSEEK,
                PersonalContextMode.AUTO,
                pironiHome
        );

        assertEquals("", context.soul());
        assertEquals("", context.userProfile());
    }
}
