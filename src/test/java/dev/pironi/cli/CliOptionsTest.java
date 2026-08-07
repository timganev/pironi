package dev.pironi.cli;

import dev.pironi.safety.ApprovalMode;
import dev.pironi.status.StatusMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliOptionsTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void bareOptionsUseInitialLocalModel() {
        CliOptions options = CliOptions.parse(new String[0], Map.of());

        assertEquals("qwen3.6:35b-a3b", options.model());
        assertTrue(options.interactive());
    }

    @Test
    void interactiveDefaultsMatchLocalPironiWorkspace() {
        CliOptions options = CliOptions.parse(
                new String[]{"--model", "qwen3.6:35b-a3b"},
                Map.of()
        );

        assertTrue(options.interactive());
        assertEquals(null, options.task());
        assertEquals(Path.of("/home/tim/repos/pironi"), options.workspace());
        assertEquals(ApprovalMode.READ_ONLY, options.approvalMode());
        assertEquals(StatusMode.ALWAYS, options.statusMode());
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
    void denyToolsDefaultsToEmpty() {
        CliOptions options = CliOptions.parse(
                new String[]{"--model", "qwen3.6:35b-a3b"},
                Map.of()
        );

        assertEquals(Set.of(), options.denyTools());
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
}
