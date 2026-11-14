package dev.pironi.verification;

import dev.pironi.safety.Workspace;
import dev.pironi.tool.PlatformShell;
import dev.pironi.tool.ProcessEnvironment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

public final class ProjectVerificationGate implements VerificationGate {
    private static final int MAX_OUTPUT_CHARACTERS = dev.pironi.tool.ToolOutput.MAX_CHARACTERS;

    private final Workspace workspace;
    private final String overrideCommand;
    private final Duration timeout;
    private boolean changed;
    /** Code files of the detected build changed since the last passing run. */
    private boolean codeChanged;

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
    public void markChanged(List<String> paths) {
        changed = true;
        Build build = detect();
        if (build != null && paths.stream().anyMatch(build::covers)) codeChanged = true;
    }

    @Override
    public boolean required() {
        if (!overrideCommand.isBlank()) return changed;
        return codeChanged && detect() != null;
    }

    @Override
    public VerificationResult verifyIfRequired() {
        if (!required()) {
            return VerificationResult.notRequired();
        }

        String command = activeCommand();
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
                codeChanged = false;
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

    /** Only what the user configured. */
    public String command() {
        return overrideCommand;
    }

    private String activeCommand() {
        if (!overrideCommand.isBlank()) return overrideCommand;
        Build build = detect();
        return build == null ? "" : build.command();
    }

    /**
     * The project's own test command, run only when this session changed one of its code files and
     * only when the agent says it is done. Detecting a build used to run the whole suite after any
     * change at all - 36 seconds to confirm deleting a temp file (removed 20.08) - so a change no
     * test can judge, a note, a CSV, a moved report, no longer triggers it.
     */
    record Build(String command, List<String> extensions) {
        boolean covers(String path) {
            String lower = path.toLowerCase(java.util.Locale.ROOT);
            return extensions.stream().anyMatch(lower::endsWith);
        }
    }

    Build detect() {
        Path root = workspace.root();
        try {
            if (Files.isRegularFile(root.resolve("pom.xml"))) {
                String mvn = Files.isExecutable(root.resolve("mvnw")) ? "./mvnw" : "mvn";
                return new Build(mvn + " -q test", List.of(".java", ".kt"));
            }
            Path packageJson = root.resolve("package.json");
            if (Files.isRegularFile(packageJson)
                    && Files.readString(packageJson).matches("(?s).*\"scripts\"\\s*:\\s*\\{[^}]*\"test\"\\s*:.*")) {
                return new Build("npm test --silent", List.of(".js", ".jsx", ".ts", ".tsx", ".mjs", ".cjs"));
            }
            Path pyproject = root.resolve("pyproject.toml");
            if (Files.isRegularFile(root.resolve("pytest.ini")) || Files.isRegularFile(root.resolve("conftest.py"))
                    || Files.isRegularFile(pyproject) && Files.readString(pyproject).contains("[tool.pytest")) {
                return new Build("python3 -m pytest -q", List.of(".py"));
            }
        } catch (IOException e) {
            return null;
        }
        return null;
    }

}
