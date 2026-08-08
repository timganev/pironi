package dev.pironi.agent;

import dev.pironi.model.ProviderType;
import dev.pironi.safety.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

        assertTrue(context.soul().endsWith("local soul"));
        assertTrue(context.userProfile().endsWith("local user"));
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

    @Test
    void customPironiHomePrefersLocalPersonalContext() throws Exception {
        Path workspaceRoot = Files.createDirectory(temporaryDirectory.resolve("local-workspace"));
        Path portableHome = Files.createDirectory(temporaryDirectory.resolve("portable-home"));
        Files.writeString(portableHome.resolve("SOUL.md"), "portable soul");
        Files.writeString(portableHome.resolve("USER.md"), "portable user");

        AgentContext context = ContextFileLoader.load(
                new Workspace(workspaceRoot),
                ProviderType.DEEPSEEK,
                PersonalContextMode.ALLOW,
                portableHome
        );

        assertTrue(context.soul().endsWith("portable soul"));
        assertTrue(context.userProfile().endsWith("portable user"));
    }

    @Test
    void customPironiHomeFallsBackToUserPironiDirectory() throws Exception {
        String previousUserHome = System.getProperty("user.home");
        Path userHome = Files.createDirectory(temporaryDirectory.resolve("user-home"));
        Path defaultPironiHome = Files.createDirectories(userHome.resolve(".pironi"));
        Path portableHome = Files.createDirectory(temporaryDirectory.resolve("empty-portable-home"));
        Path workspaceRoot = Files.createDirectory(temporaryDirectory.resolve("fallback-workspace"));
        Files.writeString(defaultPironiHome.resolve("SOUL.md"), "fallback soul");
        Files.writeString(defaultPironiHome.resolve("USER.md"), "fallback user");

        try {
            System.setProperty("user.home", userHome.toString());
            AgentContext context = ContextFileLoader.load(
                    new Workspace(workspaceRoot),
                    ProviderType.DEEPSEEK,
                    PersonalContextMode.ALLOW,
                    portableHome
            );

            assertEquals("fallback soul", context.soul());
            assertEquals("fallback user", context.userProfile());
        } finally {
            System.setProperty("user.home", previousUserHome);
        }
    }

    @Test
    void personalContextCascadesFromGlobalThroughPortableToNearestWorkspace() throws Exception {
        String previousUserHome = System.getProperty("user.home");
        Path userHome = Files.createDirectory(temporaryDirectory.resolve("cascade-user"));
        Path globalHome = Files.createDirectories(userHome.resolve(".pironi"));
        Path portableHome = Files.createDirectory(temporaryDirectory.resolve("cascade-portable"));
        Path project = Files.createDirectories(userHome.resolve("Documents/project"));
        Path projectHome = Files.createDirectory(project.resolve(".pironi"));
        Files.writeString(globalHome.resolve("SOUL.md"), "global soul");
        Files.writeString(portableHome.resolve("SOUL.md"), "portable soul");
        Files.writeString(projectHome.resolve("SOUL.md"), "project soul");

        try {
            System.setProperty("user.home", userHome.toString());
            AgentContext context = ContextFileLoader.load(
                    new Workspace(project),
                    ProviderType.DEEPSEEK,
                    PersonalContextMode.ALLOW,
                    portableHome
            );

            assertOrdered(context.soul(), "global soul", "portable soul", "project soul");
            assertTrue(context.soul().contains("Later layers override earlier layers"));
        } finally {
            System.setProperty("user.home", previousUserHome);
        }
    }

    @Test
    void claudeInstructionsCascadeFromUserHomeToWorkspace() throws Exception {
        String previousUserHome = System.getProperty("user.home");
        Path userHome = Files.createDirectory(temporaryDirectory.resolve("claude-user"));
        Path parent = Files.createDirectories(userHome.resolve("Documents"));
        Path project = Files.createDirectory(parent.resolve("project"));
        Files.writeString(userHome.resolve("CLAUDE.md"), "global project rules");
        Files.writeString(parent.resolve("CLAUDE.md"), "documents rules");
        Files.writeString(project.resolve("CLAUDE.md"), "nearest project rules");

        try {
            System.setProperty("user.home", userHome.toString());
            AgentContext context = ContextFileLoader.load(
                    new Workspace(project),
                    ProviderType.DEEPSEEK,
                    PersonalContextMode.DENY,
                    Files.createDirectory(temporaryDirectory.resolve("claude-pironi-home"))
            );

            assertOrdered(
                    context.projectInstructions(),
                    "global project rules", "documents rules", "nearest project rules"
            );
        } finally {
            System.setProperty("user.home", previousUserHome);
        }
    }

    private static void assertOrdered(String text, String... fragments) {
        int previous = -1;
        for (String fragment : fragments) {
            int current = text.indexOf(fragment);
            assertTrue(current > previous, "Expected ordered fragment: " + fragment);
            previous = current;
        }
    }
}
