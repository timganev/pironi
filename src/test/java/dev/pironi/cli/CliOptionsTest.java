package dev.pironi.cli;

import dev.pironi.safety.ApprovalMode;
import dev.pironi.status.StatusMode;
import dev.pironi.tool.ShellScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliOptionsTest {

    @Test
    void aTaskAlreadyReducedToQuestionMarksIsRecognised() {
        // Only meaningful where the launcher decoded arguments with a non-UTF-8 code page; the
        // check reads the property rather than the platform so both paths are exercised here.
        String encoding = System.getProperty("sun.jnu.encoding", "");
        boolean lossy = !encoding.equalsIgnoreCase("UTF-8");

        assertEquals(lossy, CliOptions.looksMangledByConsoleEncoding("Echo back: ???????"));
        // One question mark is a question, not a loss.
        assertTrue(!CliOptions.looksMangledByConsoleEncoding("What is the build command?"));
        assertTrue(!CliOptions.looksMangledByConsoleEncoding("Здравей"));
        assertTrue(!CliOptions.looksMangledByConsoleEncoding(""));
        assertTrue(!CliOptions.looksMangledByConsoleEncoding(null));
    }

    @TempDir
    Path temporaryDirectory;

    @Test
    void bareOptionsUseInitialLocalModel() {
        CliOptions options = CliOptions.parse(new String[0], Map.of());

        assertEquals("qwen3.6:35b-a3b", options.model());
        assertTrue(options.interactive());
    }

    @Test
    void interactiveDefaultsToCurrentWorkingDirectory() {
        CliOptions options = CliOptions.parse(
                new String[]{"--model", "qwen3.6:35b-a3b"},
                Map.of()
        );

        assertTrue(options.interactive());
        assertEquals(null, options.task());
        assertEquals(Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize(),
                options.workspace());
        assertEquals(ApprovalMode.READ_ONLY, options.approvalMode());
        assertEquals(StatusMode.AUTO, options.statusMode());
    }

    @Test
    void autoActivityOverridesAskApproval() {
        CliOptions options = CliOptions.parse(
                new String[]{
                        "--model", "qwen3.6:35b-a3b",
                        "--approval", "ask",
                        "--activity", "auto"
                },
                Map.of()
        );

        assertEquals(ApprovalMode.AUTO, options.approvalMode());
    }

    @Test
    void activityRejectsUnknownModes() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> CliOptions.parse(
                        new String[]{
                                "--model", "qwen3.6:35b-a3b",
                                "--activity", "sometimes"
                        },
                        Map.of()
                )
        );

        assertTrue(error.getMessage().contains("Unknown activity mode"));
    }

    @Test
    void rejectsUnknownOptionsAndSuggestsTheClosestKnownOption() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> CliOptions.parse(
                        new String[]{"--model", "qwen3.6:35b-a3b", "--activty", "auto"},
                        Map.of()
                )
        );

        assertEquals("Unknown option: --activty. Did you mean --activity?", error.getMessage());
    }

    @Test
    void rejectsUnknownBooleanLookingFlagsBeforeTreatingThemAsValues() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> CliOptions.parse(
                        new String[]{"--model", "qwen3.6:35b-a3b", "--mystery"},
                        Map.of()
                )
        );

        assertTrue(error.getMessage().startsWith("Unknown option: --mystery"));
    }


    @Test
    void noInteractiveRequiresTask() {
        assertThrows(
                IllegalArgumentException.class,
                () -> CliOptions.parse(
                        new String[]{"--model", "qwen3.6:35b-a3b", "--no-interactive"},
                        Map.of()
                )
        );
    }

    @Test
    void noInteractiveAcceptsOneShotTask() {
        CliOptions options = CliOptions.parse(
                new String[]{
                        "--model", "qwen3.6:35b-a3b",
                        "--no-interactive",
                        "--task", "inspect"
                },
                Map.of()
        );

        assertEquals(false, options.interactive());
        assertEquals("inspect", options.task());
    }

    @Test
    void taskFileReadsUtf8WithoutShellArgumentEncoding() throws Exception {
        Path taskFile = Files.writeString(
                temporaryDirectory.resolve("задача.txt"),
                "Редактирай файл „данни“. ✓",
                java.nio.charset.StandardCharsets.UTF_8
        );

        CliOptions options = CliOptions.parse(new String[]{
                "--model", "qwen3.6:35b-a3b",
                "--no-interactive",
                "--task-file", taskFile.toString()
        }, Map.of());

        assertEquals("Редактирай файл „данни“. ✓", options.task());
    }

    @Test
    void taskAndTaskFileAreMutuallyExclusive() throws Exception {
        Path taskFile = Files.writeString(temporaryDirectory.resolve("task.txt"), "inspect");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> CliOptions.parse(new String[]{
                        "--model", "qwen3.6:35b-a3b",
                        "--task", "inspect",
                        "--task-file", taskFile.toString()
                }, Map.of())
        );

        assertEquals("--task and --task-file cannot be used together", error.getMessage());
    }

    @Test
    void denyToolsDefaultsToEmpty() {
        CliOptions options = CliOptions.parse(
                new String[]{"--model", "qwen3.6:35b-a3b"},
                Map.of()
        );

        assertEquals(Set.of(), options.denyTools());
        assertEquals(Set.of(), options.allowTools());
        assertEquals(ShellScope.WORKSPACE, options.shellScope());
        assertEquals(List.of(options.workspace()), options.searchRoots());
    }

    @Test
    void parsesAllowToolsShellScopeAndSearchRoots() {
        CliOptions options = CliOptions.parse(new String[]{
                "--model", "qwen3.6:35b-a3b",
                "--allow-tools", "read_file,find_files",
                "--shell-scope", "user",
                "--search-roots", temporaryDirectory + "," + temporaryDirectory.resolve("docs")
        }, Map.of());

        assertEquals(Set.of("read_file", "find_files"), options.allowTools());
        assertEquals(ShellScope.USER, options.shellScope());
        assertEquals(2, options.searchRoots().size());
    }

    @Test
    void launcherEnvironmentProvidesDefaultsThatCliCanOverride() {
        Path launcherWorkspace = temporaryDirectory.resolve("launcher");
        Path explicitWorkspace = temporaryDirectory.resolve("explicit");
        Path portableHome = temporaryDirectory.resolve("portable-home");
        Map<String, String> environment = Map.of(
                "PIRONI_DEFAULT_WORKSPACE", launcherWorkspace.toString(),
                "PIRONI_DEFAULT_SEARCH_ROOTS", launcherWorkspace.toString(),
                "PIRONI_DEFAULT_HOME", portableHome.toString(),
                "PIRONI_DEFAULT_PERSONAL_CONTEXT", "allow",
                "PIRONI_DEFAULT_SHELL_SCOPE", "user"
        );

        CliOptions defaults = CliOptions.parse(new String[]{"--model", "test"}, environment);
        CliOptions overridden = CliOptions.parse(new String[]{
                "--model", "test", "--workspace", explicitWorkspace.toString(),
                "--search-roots", explicitWorkspace.toString(),
                "--personal-context", "deny", "--shell-scope", "workspace"
        }, environment);

        assertEquals(launcherWorkspace.toAbsolutePath(), defaults.workspace());
        assertEquals(List.of(launcherWorkspace.toAbsolutePath()), defaults.searchRoots());
        assertEquals(portableHome.toAbsolutePath(), defaults.pironiHome());
        assertEquals(dev.pironi.agent.PersonalContextMode.ALLOW, defaults.personalContextMode());
        assertEquals(ShellScope.USER, defaults.shellScope());
        assertEquals(explicitWorkspace.toAbsolutePath(), overridden.workspace());
        assertEquals(List.of(explicitWorkspace.toAbsolutePath()), overridden.searchRoots());
        assertEquals(dev.pironi.agent.PersonalContextMode.DENY, overridden.personalContextMode());
        assertEquals(ShellScope.WORKSPACE, overridden.shellScope());
    }

    @Test
    void allowAndDenyToolsConflict() {
        assertThrows(IllegalArgumentException.class, () -> CliOptions.parse(new String[]{
                "--model", "qwen3.6:35b-a3b",
                "--allow-tools", "read_file",
                "--deny-tools", "run_command"
        }, Map.of()));
    }

    @Test
    void subagentTimeoutSecondsParsesAndDefaults() {
        CliOptions options = CliOptions.parse(
                new String[]{"--model", "deepseek-v4-pro", "--max-subagents", "3",
                        "--subagent-timeout-seconds", "45"},
                Map.of()
        );
        assertEquals(3, options.maxSubagents());
        assertEquals(45, options.subagentTimeoutSeconds());
    }

    @Test
    void subagentTimeoutSecondsDefaultsToOneTwenty() {
        CliOptions options = CliOptions.parse(
                new String[]{"--model", "deepseek-v4-pro"},
                Map.of()
        );
        assertEquals(120, options.subagentTimeoutSeconds());
    }

    @Test
    void subagentTimeoutSecondsRejectsZero() {
        assertThrows(IllegalArgumentException.class, () -> CliOptions.parse(
                new String[]{"--model", "deepseek-v4-pro", "--subagent-timeout-seconds", "0"},
                Map.of()
        ));
    }

    @Test
    void denyToolsParsesCommaSeparatedNames() {
        CliOptions options = CliOptions.parse(
                new String[]{
                        "--model", "qwen3.6:35b-a3b",
                        "--deny-tools", "read_file, list_files"
                },
                Map.of()
        );

        assertEquals(Set.of("read_file", "list_files"), options.denyTools());
    }

    @Test
    void deepSeekUsesDirectProviderDefaults() {
        CliOptions options = CliOptions.parse(
                new String[]{"--provider", "deepseek"},
                Map.of("DEEPSEEK_API_KEY", "test-deepseek-key"),
                temporaryDirectory.resolve("missing.env")
        );

        assertEquals("deepseek-v4-pro", options.model());
        assertEquals("https://api.deepseek.com", options.baseUri().toString());
        assertEquals("test-deepseek-key", options.apiKey());
        assertEquals(1_000_000, options.contextSize());
    }

    @Test
    void deepSeekDoesNotFallBackToOpenAiKey() {
        assertThrows(
                IllegalArgumentException.class,
                () -> CliOptions.parse(
                        new String[]{"--provider", "deepseek"},
                        Map.of("OPENAI_API_KEY", "wrong-provider-key"),
                        temporaryDirectory.resolve("missing.env")
                )
        );
    }

    @Test
    void deepSeekLoadsKeyFromHermesEnvironmentFile() throws Exception {
        Path hermesEnvironment = Files.writeString(
                temporaryDirectory.resolve("hermes.env"),
                "OTHER=value\nDEEPSEEK_API_KEY=hermes-key\n"
        );

        CliOptions options = CliOptions.parse(
                new String[]{"--provider", "deepseek"},
                Map.of(),
                hermesEnvironment
        );

        assertEquals("hermes-key", options.apiKey());
    }

    @Test
    void openRouterUsesProviderDefaultsAndHermesKey() throws Exception {
        Path hermesEnvironment = Files.writeString(
                temporaryDirectory.resolve("openrouter.env"),
                "OPENROUTER_API_KEY=openrouter-key\n"
        );

        CliOptions options = CliOptions.parse(
                new String[]{"--provider", "openrouter"},
                Map.of(),
                hermesEnvironment
        );

        assertEquals("openrouter/auto", options.model());
        assertEquals("https://openrouter.ai/api/v1", options.baseUri().toString());
        assertEquals("OPENROUTER_API_KEY", options.apiKeyEnvironmentName());
        assertEquals("openrouter-key", options.apiKey());
        assertEquals(200_000, options.contextSize());
    }

    @Test
    void deepSeekVendorModelSwitchesTheCompleteProfileToOpenRouter() {
        CliOptions deepSeek = CliOptions.parse(
                new String[]{"--provider", "deepseek"},
                Map.of("DEEPSEEK_API_KEY", "deepseek-key"),
                temporaryDirectory.resolve("missing.env")
        );

        CliOptions switched = PironiMain.switchedOptions(
                deepSeek,
                "anthropic/claude-sonnet-4",
                Map.of("OPENROUTER_API_KEY", "openrouter-key"),
                temporaryDirectory.resolve("missing.env")
        );

        assertEquals(dev.pironi.model.ProviderType.OPENROUTER, switched.provider());
        assertEquals("https://openrouter.ai/api/v1", switched.baseUri().toString());
        assertEquals("anthropic/claude-sonnet-4", switched.model());
        assertEquals("openrouter-key", switched.apiKey());
        assertEquals(200_000, switched.contextSize());
    }

    @Test
    void deepSeekAllowsModelsOutsideAStaticAllowlist() {
        CliOptions deepSeek = CliOptions.parse(
                new String[]{"--provider", "deepseek"},
                Map.of("DEEPSEEK_API_KEY", "deepseek-key"),
                temporaryDirectory.resolve("missing.env")
        );

        CliOptions switched = PironiMain.switchedOptions(
                deepSeek,
                "deepseek-future-model",
                Map.of(),
                temporaryDirectory.resolve("missing.env")
        );

        assertEquals("deepseek-future-model", switched.model());
        assertEquals(dev.pironi.model.ProviderType.DEEPSEEK, switched.provider());
    }

    @Test
    void maxSubagentsDefaultsToTwoAndParsesExplicitValue() {
        CliOptions bare = CliOptions.parse(new String[0], Map.of());
        assertEquals(2, bare.maxSubagents());

        CliOptions custom = CliOptions.parse(
                new String[]{"--model", "qwen3.6:35b-a3b", "--max-subagents", "4"},
                Map.of()
        );
        assertEquals(4, custom.maxSubagents());
    }

    @Test
    void resumeNamesASessionAndContinueLeavesItToTheWorkspace() {
        // A crashed headless run printed its session id and then had no way to use it: /resume
        // exists only in the interactive shell, so the work on disk was restarted from zero.
        CliOptions named = CliOptions.parse(
                new String[]{"--model", "m", "--resume", "2026-08-21T2148-ws-abc"}, Map.of());
        assertEquals("2026-08-21T2148-ws-abc", named.resumeSession());

        CliOptions latest = CliOptions.parse(new String[]{"--model", "m", "--continue"}, Map.of());
        assertEquals("", latest.resumeSession());

        assertNull(CliOptions.parse(new String[]{"--model", "m"}, Map.of()).resumeSession());
    }
}
