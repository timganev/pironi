package dev.pironi.cli;

import com.fasterxml.jackson.databind.JsonNode;
import dev.pironi.tool.Tool;
import dev.pironi.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.Map;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PironiMainTest {
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
    void autoActivityDisablesWorkspaceShellUnlessExplicitlyAllowed() {
        CliOptions options = CliOptions.parse(
                new String[]{"--activity", "auto", "--model", "test"}, Map.of()
        );

        assertEquals(Set.of("run_command"), PironiMain.autoSafeDeniedTools(options));

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
                        "--activity", "auto", "--model", "test",
                        "--shell-scope", "user"
                },
                Map.of()
        );
        assertEquals(Set.of(), PironiMain.autoSafeDeniedTools(userScoped));
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
                return false;
            }

            @Override
            public ToolResult execute(JsonNode arguments) {
                return ToolResult.success("unused");
            }
        };
    }
}
