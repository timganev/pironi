package dev.pironi.tool;

import com.fasterxml.jackson.databind.JsonNode;
import dev.pironi.safety.Workspace;

import java.io.IOException;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

public final class RunCommandTool implements Tool {
    private final Workspace workspace;
    private final Duration defaultTimeout;
    private final int maxOutputCharacters;
    private final ShellScope shellScope;

    public RunCommandTool(Workspace workspace, Duration defaultTimeout, int maxOutputCharacters) {
        this(workspace, defaultTimeout, maxOutputCharacters, ShellScope.WORKSPACE);
    }

    public RunCommandTool(Workspace workspace, Duration defaultTimeout, int maxOutputCharacters,
            ShellScope shellScope) {
        this.workspace = workspace;
        this.defaultTimeout = defaultTimeout;
        this.maxOutputCharacters = maxOutputCharacters;
        this.shellScope = shellScope;
    }

    @Override
    public String name() {
        return "run_command";
    }

    @Override
    public String description() {
        return "Run a shell command with the workspace as current directory. "
                + "The command inherits the Pironi process environment and network access, "
                + "so tools such as curl may retrieve current external information.";
    }

    @Override
    public String argumentSchema() {
        return "{\"command\":\"string, required\",\"timeoutSeconds\":\"integer, optional, max 300\"}";
    }

    @Override
    public boolean mutating() {
        return true;
    }

    @Override
    public boolean requiresVerification() {
        return false;
    }

    @Override
    public ToolResult execute(JsonNode arguments) {
        try {
            String command = ToolArguments.requiredText(arguments, "command");
            String rejection = CommandScopePolicy.rejection(command, shellScope);
            if (rejection != null) return ToolResult.failure(rejection);
            int timeoutSeconds = ToolArguments.optionalPositiveInt(
                    arguments,
                    "timeoutSeconds",
                    Math.toIntExact(defaultTimeout.toSeconds()),
                    300
            );

            ProcessBuilder processBuilder = new ProcessBuilder(PlatformShell.command(command))
                    .directory(workspace.root().toFile())
                    .redirectErrorStream(true);
            useCurrentJavaRuntime(processBuilder.environment());
            Process process = processBuilder.start();

            FutureTask<byte[]> outputFuture = new FutureTask<>(() -> {
                try {
                    return process.getInputStream().readAllBytes();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            Thread.startVirtualThread(outputFuture);

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                return ToolResult.failure("Command timed out after " + timeoutSeconds + " seconds");
            }

            String output = new String(outputFuture.get(), StandardCharsets.UTF_8);
            if (output.length() > maxOutputCharacters) {
                output = output.substring(0, maxOutputCharacters)
                        + "\n[truncated after " + maxOutputCharacters + " characters]";
            }
            String result = "exitCode=" + process.exitValue() + "\n" + output;
            return process.exitValue() == 0
                    ? ToolResult.success(result)
                    : ToolResult.failure(result);
        } catch (IllegalArgumentException | IOException | ExecutionException e) {
            return ToolResult.failure(e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.failure("Command interrupted");
        }
    }

    static void useCurrentJavaRuntime(Map<String, String> environment) {
        String javaHome = System.getProperty("java.home");
        environment.put("JAVA_HOME", javaHome);
        String javaBin = Path.of(javaHome, "bin").toString();
        String path = environment.getOrDefault("PATH", "");
        environment.put("PATH", path.isBlank() ? javaBin : javaBin + File.pathSeparator + path);
    }
}
