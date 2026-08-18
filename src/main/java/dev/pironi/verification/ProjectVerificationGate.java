package dev.pironi.verification;

import dev.pironi.safety.Workspace;
import dev.pironi.tool.PlatformShell;
import dev.pironi.tool.ProcessEnvironment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

public final class ProjectVerificationGate implements VerificationGate {
    private static final int MAX_OUTPUT_CHARACTERS = dev.pironi.tool.ToolOutput.MAX_CHARACTERS;

    private final Workspace workspace;
    private final String command;
    private final Duration timeout;
    private boolean changed;

    public ProjectVerificationGate(Workspace workspace, String overrideCommand, Duration timeout) {
        this.workspace = workspace;
        this.command = overrideCommand == null || overrideCommand.isBlank()
                ? detectCommand(workspace, System.getProperty("os.name", "")).orElse("")
                : overrideCommand;
        this.timeout = timeout;
    }

    @Override
    public void markChanged() {
        changed = true;
    }

    @Override
    public boolean required() {
        return changed && !command.isBlank();
    }

    @Override
    public VerificationResult verifyIfRequired() {
        if (!required()) {
            return VerificationResult.notRequired();
        }

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

    public String command() {
        return command;
    }

    static Optional<String> detectCommand(Workspace workspace, String osName) {
        boolean windows = osName.toLowerCase().contains("win");
        if (Files.isRegularFile(workspace.root().resolve("mvnw.cmd")) && windows) {
            return Optional.of("mvnw.cmd test");
        }
        if (Files.isRegularFile(workspace.root().resolve("mvnw")) && !windows) {
            return Optional.of("./mvnw test");
        }
        if (Files.isRegularFile(workspace.root().resolve("pom.xml"))) {
            return Optional.of("mvn test");
        }
        if (Files.isRegularFile(workspace.root().resolve("gradlew.bat")) && windows) {
            return Optional.of("gradlew.bat test");
        }
        if (Files.isRegularFile(workspace.root().resolve("gradlew")) && !windows) {
            return Optional.of("./gradlew test");
        }
        if (Files.isRegularFile(workspace.root().resolve("build.gradle"))
                || Files.isRegularFile(workspace.root().resolve("build.gradle.kts"))) {
            return Optional.of("gradle test");
        }
        return Optional.empty();
    }
}
