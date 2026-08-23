package dev.pironi.tool;

import com.fasterxml.jackson.databind.JsonNode;
import dev.pironi.safety.Workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class RunCommandTool implements Tool {
    private final Workspace workspace;
    private final Duration defaultTimeout;
    private final int maxOutputCharacters;
    private final ShellScope shellScope;
    private final boolean promptable;

    public RunCommandTool(Workspace workspace, Duration defaultTimeout, int maxOutputCharacters) {
        this(workspace, defaultTimeout, maxOutputCharacters, ShellScope.WORKSPACE, false);
    }

    public RunCommandTool(Workspace workspace, Duration defaultTimeout, int maxOutputCharacters,
            ShellScope shellScope) {
        this(workspace, defaultTimeout, maxOutputCharacters, shellScope, false);
    }

    public RunCommandTool(Workspace workspace, Duration defaultTimeout, int maxOutputCharacters,
            ShellScope shellScope, boolean promptable) {
        this.workspace = workspace;
        this.defaultTimeout = defaultTimeout;
        this.maxOutputCharacters = maxOutputCharacters;
        this.shellScope = shellScope;
        this.promptable = promptable;
    }

    /**
     * A shell command's effect cannot be read off its name, so it is confirmed rather than
     * auto-approved - which keeps the shell usable under approval=auto instead of switched off.
     */
    @Override public boolean requiresExplicitApproval(JsonNode arguments) {
        // A call that only reads has nothing to approve.
        if (!mutating(arguments)) return false;
        return promptable;
    }

    @Override public String approvalPreview(JsonNode arguments) {
        JsonNode command = arguments == null ? null : arguments.get("command");
        return command == null || !command.isTextual()
                ? "Invalid shell request: " + arguments
                : command.textValue() + "\n  in " + workspace.root();
    }

    @Override
    public String name() {
        return "run_command";
    }

    @Override
    public String description() {
        return "Run a shell command with the workspace as current directory. "
                + "The command inherits the Pironi process environment and network access, "
                + "so tools such as curl may retrieve current external information. "
                + reach();
    }

    /**
     * The file tools name their roots and this named only its working directory, so an agent
     * reading both concluded the roots bound everything and stopped looking further.
     */
    private String reach() {
        return switch (shellScope) {
            case WORKSPACE -> "Paths outside the workspace are rejected; reach those with the "
                    + "file tools instead.";
            case USER -> "This shell reads any path the account can, including paths outside "
                    + "the roots that list_files and find_files accept.";
            case UNRESTRICTED -> "This shell reads any path the account can, with no restriction "
                    + "beyond the operating system's own.";
        };
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
    public boolean mutating(JsonNode arguments) {
        JsonNode command = arguments == null ? null : arguments.get("command");
        return command == null || !command.isTextual()
                || !ReadOnlyCommand.isReadOnly(command.textValue());
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
            ProcessEnvironment.useCurrentJavaRuntime(processBuilder.environment());
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
                String partial = partialOutput(outputFuture);
                return ToolResult.failure(
                        "Command timed out after " + timeoutSeconds + " seconds"
                                + (partial.isEmpty()
                                        ? " and printed nothing before it was stopped"
                                        : ". What it printed before it was stopped:\n" + partial)
                );
            }

            String output = truncate(new String(outputFuture.get(), StandardCharsets.UTF_8));
            int exitCode = process.exitValue();
            String result = "exitCode=" + exitCode + cause(exitCode, PlatformShell.name())
                    + answerNotFailure(exitCode, output)
                    + "\n" + output;
            // A command that ran and answered is not a failed tool call, whatever it exited with.
            // Reporting grep's "no matches" as a failure had the agent rewriting scripts that were
            // working, twice in one run. What stays a failure is a command that could not run at
            // all - not found, not executable, killed - or one that exited non-zero saying nothing,
            // where there is no answer to read either way.
            return exitCode == 0 || answered(exitCode, output)
                    ? ToolResult.success(result)
                    : ToolResult.failure(result);
        } catch (IllegalArgumentException | IOException | ExecutionException e) {
            return ToolResult.failure(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.failure("Command interrupted");
        }
    }

    /**
     * Some programs answer through the exit code: grep exits 1 for no matches, diff for
     * differences, both having printed what was asked.
     */
    private static String answerNotFailure(int exitCode, String output) {
        return answered(exitCode, output)
                ? " (non-zero, but the command ran and printed output: some programs report an"
                        + " answer this way - grep exits 1 for no matches, diff exits 1 for"
                        + " differences. The code below is the command's verdict, not a fault in"
                        + " running it; read the output before deciding anything failed.)"
                : "";
    }

    /**
     * Whether a non-zero exit carries an answer rather than a fault. 126 and above are the shell's
     * own codes - not executable, not found, killed by a signal - and mean the command never got
     * to say anything.
     */
    private static boolean answered(int exitCode, String output) {
        return exitCode > 0 && exitCode < 126 && !output.isBlank();
    }

    private String truncate(String output) {
        return output.length() > maxOutputCharacters
                ? output.substring(0, maxOutputCharacters)
                        + "\n[truncated after " + maxOutputCharacters + " characters]"
                : output;
    }

    /** What the command printed before it ran out of time. */
    private String partialOutput(FutureTask<byte[]> outputFuture) {
        try {
            return truncate(new String(outputFuture.get(5, TimeUnit.SECONDS), StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        } catch (ExecutionException | TimeoutException e) {
            return "";
        }
    }

    /** What a bare number does not say. */
    static String cause(int exitCode, String shell) {
        // 128+N is how a POSIX shell reports a signal. cmd.exe has no such convention: an exit code
        // there is whatever the program chose, so 137 may be an ordinary application status and
        // naming it "out of memory" would invent a cause.
        if (shell.toLowerCase(java.util.Locale.ROOT).contains("cmd")) {
            return exitCode == 9009
                    ? " (command not found - check the name, or whether it is installed)"
                    : "";
        }
        return switch (exitCode) {
            case 126 -> " (not executable)";
            case 127 -> " (command not found - check the name, or whether it is installed)";
            case 130 -> " (interrupted, SIGINT)";
            case 137 -> " (killed, SIGKILL - usually out of memory; process the data in pieces "
                    + "rather than reading it all at once)";
            case 139 -> " (crashed, SIGSEGV - the same command will crash again)";
            case 141 -> " (SIGPIPE - the reader closed the pipe early, which is what \"| head\" "
                    + "does; any output above is complete up to that point)";
            case 143 -> " (terminated, SIGTERM)";
            default -> "";
        };
    }
}
