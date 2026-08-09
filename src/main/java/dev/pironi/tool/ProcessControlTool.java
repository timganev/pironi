package dev.pironi.tool;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** Explicitly-approved single-PID termination with identity and critical-process guards. */
public final class ProcessControlTool implements Tool {
    private static final Set<String> CRITICAL = Set.of(
            "system", "registry", "smss.exe", "csrss.exe", "wininit.exe", "services.exe",
            "lsass.exe", "winlogon.exe", "dwm.exe", "explorer.exe", "systemd", "init",
            "launchd", "kernel_task", "loginwindow", "windowserver"
    );
    private final Backend backend;

    public ProcessControlTool() { this(new NativeBackend()); }
    ProcessControlTool(Backend backend) { this.backend = backend; }

    @Override public String name() { return "process_control"; }
    @Override public String description() {
        return "Terminate exactly one non-critical process by PID after process_inspect. Requires "
                + "the observed executable name to prevent PID-reuse mistakes and always asks the user "
                + "for explicit approval, including in auto mode. Actions: terminate, force-kill. "
                + "Use force-kill only after terminate failed or the user explicitly requested it.";
    }
    @Override public String argumentSchema() {
        return "{\"pid\":123,\"expectedName\":\"exact executable name from process_inspect\","
                + "\"action\":\"terminate|force-kill\"}";
    }
    @Override public boolean mutating() { return true; }
    @Override public boolean requiresVerification() { return false; }
    @Override public boolean requiresExplicitApproval(JsonNode arguments) { return true; }

    @Override public String approvalPreview(JsonNode arguments) {
        try {
            Request request = request(arguments);
            return request.action() + " PID " + request.pid() + " (expected executable: "
                    + request.expectedName() + ")";
        } catch (IllegalArgumentException e) {
            return "Invalid process control request: " + e.getMessage();
        }
    }

    @Override public ToolResult validate(JsonNode arguments) {
        try {
            Request request = request(arguments);
            return validateIdentity(request);
        } catch (IllegalArgumentException e) {
            return ToolResult.failure(e.getMessage());
        }
    }

    @Override public ToolResult execute(JsonNode arguments) {
        try {
            Request request = request(arguments);
            ToolResult identity = validateIdentity(request);
            if (!identity.success()) return identity;
            boolean requested = request.action().equals("force-kill")
                    ? backend.force(request.pid()) : backend.terminate(request.pid());
            if (!requested) return ToolResult.failure("OS rejected " + request.action()
                    + " for PID " + request.pid());
            boolean stopped = backend.awaitStopped(request.pid(), Duration.ofSeconds(5));
            return stopped ? ToolResult.success("PID " + request.pid() + " ("
                    + request.expectedName() + ") stopped after " + request.action())
                    : ToolResult.failure("PID " + request.pid() + " (" + request.expectedName()
                    + ") is still running after " + request.action());
        } catch (IllegalArgumentException e) {
            return ToolResult.failure(e.getMessage());
        } catch (Exception e) {
            return ToolResult.failure("Process control failed: " + e.getMessage());
        }
    }

    private ToolResult validateIdentity(Request request) {
        if (protectedPid(request.pid())) {
            return ToolResult.failure("Refusing to control Pironi, its ancestor, or a critical PID: "
                    + request.pid());
        }
        Optional<ProcessIdentity> current = backend.identity(request.pid());
        if (current.isEmpty()) return ToolResult.failure("PID is no longer running: " + request.pid());
        String actual = canonicalName(current.get().name());
        if (!actual.equals(canonicalName(request.expectedName()))) {
            return ToolResult.failure("PID identity changed; expected " + request.expectedName()
                    + " but found " + current.get().name() + ". Inspect again before retrying.");
        }
        if (CRITICAL.contains(actual)) {
            return ToolResult.failure("Refusing to control critical process: " + actual);
        }
        String currentUser = canonicalUser(System.getProperty("user.name", ""));
        if (!current.get().user().isBlank() && !currentUser.isBlank()
                && !canonicalUser(current.get().user()).equals(currentUser)) {
            return ToolResult.failure("Refusing to control a process owned by another user");
        }
        return ToolResult.success("validated");
    }

    private static boolean protectedPid(long pid) {
        if (pid <= 4 || pid == ProcessHandle.current().pid()) return true;
        Optional<ProcessHandle> cursor = ProcessHandle.current().parent();
        while (cursor.isPresent()) {
            if (cursor.get().pid() == pid) return true;
            cursor = cursor.get().parent();
        }
        return false;
    }

    private static Request request(JsonNode arguments) {
        JsonNode pidNode = arguments == null ? null : arguments.get("pid");
        if (pidNode == null || !pidNode.canConvertToLong() || pidNode.longValue() <= 0) {
            throw new IllegalArgumentException("pid must be a positive integer");
        }
        String expected = ToolArguments.requiredText(arguments, "expectedName").strip();
        if (expected.contains("/") || expected.contains("\\")
                || !expected.equals(Path.of(expected).getFileName().toString())
                || expected.length() > 100) {
            throw new IllegalArgumentException("expectedName must be a short executable filename");
        }
        String action = ToolArguments.requiredText(arguments, "action").strip().toLowerCase(Locale.ROOT);
        if (!action.equals("terminate") && !action.equals("force-kill")) {
            throw new IllegalArgumentException("Unsupported action: " + action);
        }
        return new Request(pidNode.longValue(), expected, action);
    }

    private static String canonicalName(String value) {
        return Path.of(value).getFileName().toString().toLowerCase(Locale.ROOT);
    }

    private static String canonicalUser(String value) {
        String normalized = value.strip().toLowerCase(Locale.ROOT).replace('\\', '/');
        int separator = normalized.lastIndexOf('/');
        return separator < 0 ? normalized : normalized.substring(separator + 1);
    }

    interface Backend {
        Optional<ProcessIdentity> identity(long pid);
        boolean terminate(long pid);
        boolean force(long pid);
        boolean awaitStopped(long pid, Duration timeout) throws InterruptedException;
    }

    record ProcessIdentity(String name, String user) { }
    private record Request(long pid, String expectedName, String action) { }

    static final class NativeBackend implements Backend {
        @Override public Optional<ProcessIdentity> identity(long pid) {
            return ProcessHandle.of(pid).filter(ProcessHandle::isAlive).flatMap(process ->
                    process.info().command().map(command -> new ProcessIdentity(
                            Path.of(command).getFileName().toString(),
                            process.info().user().orElse(""))));
        }
        @Override public boolean terminate(long pid) {
            return ProcessHandle.of(pid).filter(ProcessHandle::isAlive)
                    .map(ProcessHandle::destroy).orElse(false);
        }
        @Override public boolean force(long pid) {
            return ProcessHandle.of(pid).filter(ProcessHandle::isAlive)
                    .map(ProcessHandle::destroyForcibly).orElse(false);
        }
        @Override public boolean awaitStopped(long pid, Duration timeout) throws InterruptedException {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)
                    && System.nanoTime() < deadline) Thread.sleep(100);
            return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) == false;
        }
    }
}
