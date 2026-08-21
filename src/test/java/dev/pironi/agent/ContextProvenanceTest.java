package dev.pironi.agent;

import dev.pironi.model.ProviderType;
import dev.pironi.safety.Workspace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextProvenanceTest {
    @TempDir Path root;
    private String realHome;

    // The cascade always starts at <user.home>/.pironi, so a test that leaves it alone reads
    // the developer's own identity file and passes or fails for reasons of their machine.
    @BeforeEach
    void useASandboxHome() {
        realHome = System.getProperty("user.home");
        System.setProperty("user.home", root.resolve("home").toString());
    }

    @AfterEach
    void restoreHome() {
        System.setProperty("user.home", realHome);
    }

    @Test
    void namesTheFileTheIdentityCameFrom() throws Exception {
        Path home = Files.createDirectories(root.resolve("pironi-home"));
        Files.writeString(home.resolve("SOUL.md"), "# who you are");

        AgentContext context = load(home);

        assertTrue(context.personalSources().contains("SOUL.md"), context.personalSources());
        assertTrue(context.personalSources().contains(home.toString()), context.personalSources());
    }

    @Test
    void reportsTheSourcesToTheModel() throws Exception {
        Path home = Files.createDirectories(root.resolve("pironi-home2"));
        Files.writeString(home.resolve("SOUL.md"), "# who you are");

        String report = report(load(home));

        assertTrue(report.contains("personal context:"), report);
        assertTrue(report.contains("no tool here can write them"), report);
    }

    @Test
    void saysWhenNothingWasLoaded() throws Exception {
        String report = report(load(Files.createDirectories(root.resolve("empty"))));

        assertTrue(report.contains("no SOUL.md or USER.md was loaded"), report);
        assertFalse(report.contains("no tool here can write them"), report);
    }

    @Test
    void pointsAtAFileWhoseNameIsOnlyAlmostRight() throws Exception {
        // On macOS and Windows this file loads; on Linux it is silently a different file.
        Path home = Files.createDirectories(root.resolve("pironi-home3"));
        Files.writeString(home.resolve("soul.md"), "# lower case");

        String sources = load(home).personalSources();

        if (Files.exists(home.resolve("SOUL.md"))) {
            // Case-insensitive filesystem: it was read, so there is no near miss to report.
            assertTrue(sources.contains("soul.md") || sources.contains("SOUL.md"), sources);
        } else {
            assertTrue(sources.contains("ignored"), sources);
            assertTrue(sources.contains("on Linux this spelling is a different file"), sources);
        }
    }

    private AgentContext load(Path pironiHome) throws Exception {
        Path workspace = Files.createDirectories(root.resolve("work-" + pironiHome.getFileName()));
        return ContextFileLoader.load(
                new Workspace(workspace), ProviderType.OLLAMA, PersonalContextMode.ALLOW,
                pironiHome);
    }

    private static String report(AgentContext context) {
        return new CapabilityReport(
                new dev.pironi.tool.ToolRegistry(java.util.List.of()), context).render();
    }
}
