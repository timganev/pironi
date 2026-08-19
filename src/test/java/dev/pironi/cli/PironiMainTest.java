package dev.pironi.cli;

import com.fasterxml.jackson.databind.JsonNode;
import dev.pironi.tool.Tool;
import dev.pironi.tool.ToolResult;
import dev.pironi.status.StatusMode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.Map;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PironiMainTest {
    @Test
    void namesTheShellAndTheReasonWhenRunCommandIsWithheld() {
        String notice = PironiMain.runCommandDisabledNotice(
                java.util.Map.of("run_command", "blocked by auto-safe workspace policy")
        );

        assertTrue(notice.contains("run_command is blocked by auto-safe workspace policy"));
        assertTrue(notice.contains(dev.pironi.tool.PlatformShell.name()));
    }

    @Test
    void saysNothingWhenTheShellIsAvailable() {
        assertEquals("", PironiMain.runCommandDisabledNotice(java.util.Map.of()));
    }

    @Test
    void readRootsFollowTheShellScope() {
        Path configured = Path.of("/configured/root");
        Path home = Path.of(System.getProperty("user.home"));

        assertEquals(List.of(configured), PironiMain.readRoots(
                List.of(configured), dev.pironi.tool.ShellScope.WORKSPACE));
        // The shell could reach the whole home while the file tools could not, so the agent wrote
        // a file it could no longer read and took the search roots for the edge of the world.
        assertTrue(PironiMain.readRoots(
                List.of(configured), dev.pironi.tool.ShellScope.USER).contains(home));
        assertTrue(PironiMain.readRoots(
                List.of(configured), dev.pironi.tool.ShellScope.UNRESTRICTED).contains(home));
        assertTrue(PironiMain.readRoots(
                        List.of(configured), dev.pironi.tool.ShellScope.UNRESTRICTED).size() > 2,
                "unrestricted adds the filesystem roots as well");
    }

    @Test
    void crashHintNamesTheCheckpointedSession() {
        // The loop checkpoints every turn; a headless run that died used to print only the
        // failure, so the finished turns looked lost and the run was started again from zero.
        String hint = PironiMain.resumeHint("20260819-020000-outlook-e2e-79365308");

        assertTrue(hint.contains("/resume 20260819-020000-outlook-e2e-79365308"), hint);
        assertEquals("", PironiMain.resumeHint(""), "no session yet means no hint");
        assertEquals("", PironiMain.resumeHint(null));
    }

    @Test
    void sessionBannerCarriesVersionIdAndResumeCommand() {
        String banner = PironiMain.sessionBanner("20260810-120000-project-abc12345");
        // The build is named first: a screenshot of a reported problem should say which
        // release produced it, which was previously impossible to tell.
        assertTrue(banner.startsWith("Pironi "), banner);
        assertTrue(banner.contains("Session: 20260810-120000-project-abc12345"), banner);
        assertTrue(banner.contains("/resume 20260810-120000-project-abc12345"), banner);
    }

    @Test
    void automaticStatusRequiresAnInteractiveConsole() {
        assertEquals(false, PironiMain.statusEnabled(StatusMode.AUTO, false, "Linux"));
        assertEquals(true, PironiMain.statusEnabled(StatusMode.AUTO, true, "Linux"));
        // Legacy conhost: still excluded, that is what the exclusion was for.
        assertEquals(false, PironiMain.statusEnabled(StatusMode.AUTO, true, "Windows 11", false));
        // Windows Terminal is ANSI-capable; excluding it hid the status row AND every
        // activity line for Windows users on the default AUTO mode.
        assertEquals(true, PironiMain.statusEnabled(StatusMode.AUTO, true, "Windows 11", true));
        assertEquals(false, PironiMain.statusEnabled(StatusMode.AUTO, false, "Windows 11", true),
                "no console means no status even in Windows Terminal");
        assertEquals(true, PironiMain.statusEnabled(StatusMode.ALWAYS, false, "Windows 11"));
        assertEquals(false, PironiMain.statusEnabled(StatusMode.NEVER, true, "Linux"));
    }

    @Test
    void deniedToolsAreAbsentFromRegistry() {
        var registry = PironiMain.configuredTools(
                List.of(tool("read_file"), tool("list_files"), tool("run_command")),
                Set.of("read_file", "list_files")
        );

        assertEquals(List.of("run_command"), registry.all().stream().map(Tool::name).toList());
    }

    @Test
    void unknownDeniedToolFailsClosed() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> PironiMain.configuredTools(
                        List.of(tool("read_file"), tool("list_files")),
                        Set.of("read_files")
                )
        );

        assertEquals(
                "Unknown tool name(s) in --deny-tools: read_files. "
                        + "Known tools: list_files,read_file",
                error.getMessage()
        );
    }

    @Test
    void allowedToolsFormAnExactRegistry() {
        var registry = PironiMain.configuredTools(
                List.of(tool("read_file"), tool("find_files"), tool("run_command")),
                Set.of(),
                Set.of("read_file", "find_files")
        );

        assertEquals(Set.of("read_file", "find_files"), registry.all().stream()
                .map(Tool::name).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void readOnlyRegistryDoesNotAdvertiseMutatingTools() {
        var configured = PironiMain.configuredTools(
                List.of(tool("read_file", false), tool("write_file", true),
                        tool("run_command", true)),
                Set.of()
        );

        var readOnly = PironiMain.toolsForApproval(
                configured, dev.pironi.safety.ApprovalMode.READ_ONLY
        );

        assertEquals(List.of("read_file"), readOnly.all().stream().map(Tool::name).toList());
        assertEquals(3, PironiMain.toolsForApproval(
                configured, dev.pironi.safety.ApprovalMode.AUTO
        ).all().size());
        assertEquals(true, configured.find("write_file").isPresent());
    }

    @Test
    void autoActivityDisablesWorkspaceShellUnlessExplicitlyAllowed() {
        // An interactive session keeps the shell: every command is confirmed before it runs
        // (RunCommandTool.requiresExplicitApproval), so auto approval never runs one unseen.
        CliOptions interactive = CliOptions.parse(
                new String[]{"--activity", "auto", "--model", "test"}, Map.of()
        );
        assertEquals(Set.of("app_control"), PironiMain.autoSafeDeniedTools(interactive));

        // A batch run cannot confirm anything, so there the shell stays off at workspace scope.
        CliOptions options = CliOptions.parse(
                new String[]{"--activity", "auto", "--model", "test", "--no-interactive",
                        "--task", "anything"},
                Map.of()
        );

        assertEquals(Set.of("run_command", "app_control"), PironiMain.autoSafeDeniedTools(options));

        CliOptions explicit = CliOptions.parse(
                new String[]{
                        "--activity", "auto", "--model", "test",
                        "--allow-tools", "run_command"
                },
                Map.of()
        );
        assertEquals(Set.of(), PironiMain.autoSafeDeniedTools(explicit));

        CliOptions userScoped = CliOptions.parse(
                new String[]{
                        "--activity", "auto", "--model", "test", "--no-interactive",
                        "--task", "anything", "--shell-scope", "user"
                },
                Map.of()
        );
        assertEquals(Set.of("app_control"), PironiMain.autoSafeDeniedTools(userScoped));
    }

    @Test
    void autoActivityDisablesAppControlAtEveryShellScope() {
        for (String scope : new String[]{"workspace", "user", "unrestricted"}) {
            CliOptions options = CliOptions.parse(
                    new String[]{
                            "--activity", "auto", "--model", "test",
                            "--shell-scope", scope
                    },
                    Map.of()
            );
            assertEquals(
                    true,
                    PironiMain.autoSafeDeniedTools(options).contains("app_control"),
                    "app_control must stay denied under auto approval at scope " + scope
            );
        }

        CliOptions explicit = CliOptions.parse(
                new String[]{
                        "--activity", "auto", "--model", "test",
                        "--allow-tools", "app_control"
                },
                Map.of()
        );
        assertEquals(Set.of(), PironiMain.autoSafeDeniedTools(explicit));
    }

    @Test
    void directDeepSeekModelSwitchAlsoSwitchesProvider() {
        CliOptions previous = CliOptions.parse(new String[0], Map.of());

        CliOptions switched = PironiMain.switchedOptions(
                previous,
                "deepseek-v4-flash",
                Map.of("DEEPSEEK_API_KEY", "secret"),
                Path.of("/missing")
        );

        assertEquals(dev.pironi.model.ProviderType.DEEPSEEK, switched.provider());
        assertEquals("deepseek-v4-flash", switched.model());
        assertEquals("https://api.deepseek.com", switched.baseUri().toString());
    }

    private static Tool tool(String name) {
        return tool(name, false);
    }

    private static Tool tool(String name, boolean mutating) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "test";
            }

            @Override
            public String argumentSchema() {
                return "{}";
            }

            @Override
            public boolean mutating() {
                return mutating;
            }

            @Override
            public ToolResult execute(JsonNode arguments) {
                return ToolResult.success("unused");
            }
        };
    }
}
