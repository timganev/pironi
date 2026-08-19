package dev.pironi.tool;

import com.fasterxml.jackson.databind.JsonNode;
import dev.pironi.safety.Workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

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
     * A shell command is the one tool whose effect cannot be read off its name, so it is shown
     * and confirmed rather than auto-approved. That is what lets the shell stay available under
     * approval=auto instead of being switched off wholesale, which is what used to happen and
     * left ordinary work - deleting a file, say - with no route at all.
     *
     * <p>Only where a prompt can actually be answered. A batch run cannot ask, and a policy that
     * always denies there would break every scripted shell step.
     */
    @Override public boolean requiresExplicitApproval(JsonNode arguments) {
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
     * The file tools name the roots they accept, and this one used to name only its working
     * directory. An agent that reads both concludes the roots bound everything there is, and
     * stops looking outside them even when the shell could reach further. Saying how far this
     * shell reaches is what makes the wider scope usable.
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
                return ToolResult.failure("Command timed out after " + timeoutSeconds + " seconds");
            }

            String output = new String(outputFuture.get(), StandardCharsets.UTF_8);
            if (output.length() > maxOutputCharacters) {
                output = output.substring(0, maxOutputCharacters)
                        + "\n[truncated after " + maxOutputCharacters + " characters]";
            }
            int exitCode = process.exitValue();
            String result = "exitCode=" + exitCode + cause(exitCode, PlatformShell.name())
                    + "\n" + output;
            return exitCode == 0
                    ? ToolResult.success(result)
                    : ToolResult.failure(result);
        } catch (IllegalArgumentException | IOException | ExecutionException e) {
            return ToolResult.failure(e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.failure("Command interrupted");
        }
    }

    /**
     * What a bare number does not say. A shell reports a signal death as 128+N, and "exitCode=137"
     * alone reads as an ordinary failure - so the same command gets tried again, or a readable
     * source gets written off as unreadable. Naming the cause is what lets the model adapt; it
     * corrected itself unprompted every time an error said something specific. Codes 1-125 are the
     * program's own and are left alone: inventing meanings for them would mislead in the other
     * direction.
     */
    static String cause(int exitCode, String shell) {
        // 128+N is how a POSIX shell reports a signal. cmd.exe has no such convention: an exit
        // code there is whatever the program chose, so 137 may be an ordinary application status
        // and naming it "out of memory" would invent a cause. Only 9009 is cmd's own.
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
