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

    @Test
    void saysWhenTheIdentityFileIsNotSpeltTheWayItWasAskedFor() throws Exception {
        // Test plan section И3. Windows and macOS open soul.md for a request for SOUL.md and used
        // to report the name that was asked for, so the same directory silently loads nothing on
        // Linux and the source line gives no hint why.
        Path home = Files.createDirectories(root.resolve("cased-home"));
        Files.writeString(home.resolve("soul.md"), "# who you are");

        AgentContext context = load(home);

        boolean caseInsensitive = Files.exists(home.resolve("SOUL.md"));
        if (caseInsensitive) {
            assertTrue(context.soul().contains("who you are"), "it should have loaded here");
            assertTrue(context.personalSources().contains("on disk it is soul.md"),
                    context.personalSources());
            assertTrue(context.personalSources().contains("would not load on Linux"),
                    context.personalSources());
        } else {
            // Where case matters the file is a different file, and the near-miss note is what
            // tells someone why their identity vanished.
            assertFalse(context.soul().contains("who you are"));
            assertTrue(context.personalSources().contains("ignored"), context.personalSources());
        }
    }

    @Test
    void anExactlySpeltIdentityFileIsReportedWithoutAWarning() throws Exception {
        Path home = Files.createDirectories(root.resolve("exact-home"));
        Files.writeString(home.resolve("SOUL.md"), "# who you are");

        AgentContext context = load(home);

        assertTrue(context.soul().contains("who you are"));
        assertFalse(context.personalSources().contains("on disk it is"),
                context.personalSources());
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
