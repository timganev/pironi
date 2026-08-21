package dev.pironi.verification;

import dev.pironi.safety.Workspace;
import dev.pironi.tool.PlatformShell;
import dev.pironi.tool.ProcessEnvironment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

public final class ProjectVerificationGate implements VerificationGate {
    private static final int MAX_OUTPUT_CHARACTERS = dev.pironi.tool.ToolOutput.MAX_CHARACTERS;

    private final Workspace workspace;
    private final String overrideCommand;
    private final Duration timeout;
    private boolean changed;

    public ProjectVerificationGate(Workspace workspace, String overrideCommand, Duration timeout) {
        this.workspace = workspace;
        this.overrideCommand = overrideCommand == null ? "" : overrideCommand;
        this.timeout = timeout;
    }

    @Override
    public void markChanged() {
        changed = true;
    }

    @Override
    public boolean required() {
        return changed && !command().isBlank();
    }

    @Override
    public VerificationResult verifyIfRequired() {
        if (!required()) {
            return VerificationResult.notRequired();
        }

        String command = command();
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(PlatformShell.command(command))
                    .directory(workspace.root().toFile())
                    .redirectErrorStream(true);
            ProcessEnvironment.useCurrentJavaRuntime(processBuilder.environment());
            Process process = processBuilder.start();
            FutureTask<byte[]> output = new FutureTask<>(
                    () -> process.getInputStream().readAllBytes()
            );
            Thread.startVirtualThread(output);

            boolean finished = process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                return new VerificationResult(
                        true,
                        false,
                        command,
                        "Verification timed out after " + timeout.toSeconds() + " seconds"
                );
            }

            String text = new String(output.get(), StandardCharsets.UTF_8);
            if (text.length() > MAX_OUTPUT_CHARACTERS) {
                text = text.substring(0, MAX_OUTPUT_CHARACTERS)
                        + "\n[truncated after " + MAX_OUTPUT_CHARACTERS + " characters]";
            }
            boolean success = process.exitValue() == 0;
            if (success) {
                changed = false;
            }
            return new VerificationResult(
                    true,
                    success,
                    command,
                    "exitCode=" + process.exitValue() + "\n" + text
            );
        } catch (IOException | ExecutionException e) {
            return new VerificationResult(true, false, command, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new VerificationResult(true, false, command, "Verification interrupted");
        }
    }

    /**
     * Only what the user configured. Detecting a build from a pom made every mutation pay for the
     * whole test suite - 36 seconds to confirm deleting a temp file, which no test can judge.
     */
    public String command() {
        return overrideCommand;
    }

}
