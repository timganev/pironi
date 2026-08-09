package dev.pironi.tool;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Allowlisted cross-platform desktop application control without arbitrary shell input. */
public final class AppControlTool implements Tool {
    private static final Map<String, App> APPS = Map.of(
            "firefox", new App("firefox", "Firefox", List.of("firefox", "firefox.exe")),
            "chrome", new App("chrome", "Google Chrome",
                    List.of("google-chrome", "google-chrome-stable", "chrome", "chrome.exe",
                            "Google Chrome")),
            "edge", new App("edge", "Microsoft Edge",
                    List.of("microsoft-edge", "msedge", "msedge.exe", "Microsoft Edge")),
            "obsidian", new App("obsidian", "Obsidian", List.of("obsidian", "obsidian.exe")),
            "vscode", new App("vscode", "Visual Studio Code", List.of("code", "code.exe")),
            "notepad", new App("notepad", "Notepad", List.of("notepad", "notepad.exe")),
            "slack", new App("slack", "Slack", List.of("slack", "slack.exe")),
            "image-viewer", new App("image-viewer", "Preview",
                    List.of("eog", "loupe", "org.gnome.Loupe", "Microsoft.Photos.exe", "Preview")),
            "settings", new App("settings", "System Settings",
                    List.of("gnome-control-center", "systemsettings", "systemsettings5",
                            "SystemSettings.exe", "System Settings"))
    );
    private static final List<String> ACTIONS = List.of("status", "launch", "new-window", "close");
    private final Backend backend;

    public AppControlTool() { this(new NativeBackend()); }
    AppControlTool(Backend backend) { this.backend = backend; }

    @Override public String name() { return "app_control"; }
    @Override public String description() {
        return "Check, launch, open a new window, or gracefully close an allowlisted desktop "
                + "application without arbitrary shell commands. Applications: firefox, chrome, "
                + "edge, obsidian, vscode, notepad, slack, image-viewer, settings. Actions: "
                + "status, launch, new-window, close.";
    }
    @Override public String argumentSchema() {
        return "{\"application\":\"firefox|chrome|edge|obsidian|vscode|notepad|slack|image-viewer|settings\","
                + "\"action\":\"status|launch|new-window|close\"}";
    }
    @Override public boolean mutating() { return true; }
    @Override public boolean requiresVerification() { return false; }

    @Override public String approvalPreview(JsonNode arguments) {
        try {
            Request request = request(arguments);
            return request.action() + " " + request.app().label();
        } catch (IllegalArgumentException e) {
            return "Invalid app control request: " + e.getMessage();
        }
    }

    @Override public ToolResult validate(JsonNode arguments) {
        try {
            request(arguments);
            return ToolResult.success("validated");
        } catch (IllegalArgumentException e) {
            return ToolResult.failure(e.getMessage());
        }
    }

    @Override public ToolResult execute(JsonNode arguments) {
        try {
            Request request = request(arguments);
            return switch (request.action()) {
                case "status" -> {
                    int count = backend.running(request.app());
                    yield ToolResult.success(count == 0
                            ? request.app().label() + " is not running"
                            : request.app().label() + " is running; processes=" + count);
                }
                case "launch", "new-window" -> {
                    backend.launch(request.app(), request.action().equals("new-window"));
                    yield ToolResult.success(request.app().label()
                            + (request.action().equals("new-window")
                            ? " new window requested" : " launch requested"));
                }
                case "close" -> {
                    int before = backend.running(request.app());
                    if (before == 0) yield ToolResult.success(request.app().label() + " is not running");
                    backend.close(request.app());
                    int remaining = backend.awaitStopped(request.app(), Duration.ofSeconds(5));
                    yield remaining == 0
                            ? ToolResult.success(request.app().label() + " closed gracefully")
                            : ToolResult.failure(request.app().label() + " did not exit gracefully; "
                            + "remainingProcesses=" + remaining + "; force-close was not attempted");
                }
                default -> throw new IllegalStateException("unreachable action");
            };
        } catch (IllegalArgumentException e) {
            return ToolResult.failure(e.getMessage());
        } catch (Exception e) {
            return ToolResult.failure("Application control failed: " + e.getMessage());
        }
    }

    private static Request request(JsonNode arguments) {
        String name = ToolArguments.requiredText(arguments, "application")
                .toLowerCase(Locale.ROOT);
        App app = APPS.get(name);
        if (app == null) throw new IllegalArgumentException("Unsupported application: " + name);
        String action = ToolArguments.requiredText(arguments, "action").toLowerCase(Locale.ROOT);
        if (!ACTIONS.contains(action)) throw new IllegalArgumentException("Unsupported action: " + action);
        return new Request(app, action);
    }

    interface Backend {
        int running(App app);
        void launch(App app, boolean newWindow) throws Exception;
        void close(App app) throws Exception;
        int awaitStopped(App app, Duration timeout) throws InterruptedException;
    }

    static final class NativeBackend implements Backend {
        private final String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

        @Override public int running(App app) {
            try (var processes = ProcessHandle.allProcesses()) {
                return Math.toIntExact(processes.filter(process -> matches(process, app)).count());
            }
        }

        @Override public void launch(App app, boolean newWindow) throws IOException {
            List<String> command = launchCommand(app, newWindow);
            new ProcessBuilder(command).start();
        }

        @Override public void close(App app) throws IOException {
            if (windows()) {
                String process = windowsProcessName(app);
                String script = "Get-Process -Name '" + process
                        + "' -ErrorAction SilentlyContinue | ForEach-Object { "
                        + "[void]$_.CloseMainWindow() }";
                startAndRequireSuccess(List.of(
                        "powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script
                ));
                return;
            }
            if (mac()) {
                startAndRequireSuccess(List.of(
                        "/usr/bin/osascript", "-e",
                        "tell application \"" + app.label() + "\" to quit"
                ));
                return;
            }
            try (var processes = ProcessHandle.allProcesses()) {
                processes.filter(process -> matches(process, app)).forEach(ProcessHandle::destroy);
            }
        }

        @Override public int awaitStopped(App app, Duration timeout) throws InterruptedException {
            long deadline = System.nanoTime() + timeout.toNanos();
            int count;
            while ((count = running(app)) > 0 && System.nanoTime() < deadline) {
                Thread.sleep(100);
            }
            return count;
        }

        private List<String> launchCommand(App app, boolean newWindow) throws IOException {
            if (mac()) return List.of("/usr/bin/open", newWindow ? "-na" : "-a", app.label());
            if (windows() && app.id().equals("image-viewer")) {
                return List.of(Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"),
                        "explorer.exe").toString(), "ms-photos:");
            }
            if (windows() && app.id().equals("settings")) {
                return List.of(Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"),
                        "explorer.exe").toString(), "ms-settings:");
            }
            Path executable = findExecutable(app);
            List<String> result = new ArrayList<>();
            result.add(executable.toString());
            if (newWindow && (app.id().equals("firefox") || app.id().equals("chrome")
                    || app.id().equals("edge"))) result.add("--new-window");
            return List.copyOf(result);
        }

        private Path findExecutable(App app) throws IOException {
            for (Path candidate : candidates(app)) {
                if (Files.isRegularFile(candidate) && (windows() || Files.isExecutable(candidate))) {
                    return candidate;
                }
            }
            throw new IOException(app.label() + " executable was not found in known locations");
        }

        private List<Path> candidates(App app) {
            List<Path> paths = new ArrayList<>();
            if (windows()) {
                String systemRoot = System.getenv().getOrDefault("SystemRoot", "C:\\Windows");
                String local = System.getenv().getOrDefault("LOCALAPPDATA", "");
                String programFiles = System.getenv().getOrDefault("ProgramFiles", "C:\\Program Files");
                String programFilesX86 = System.getenv().getOrDefault("ProgramFiles(x86)",
                        "C:\\Program Files (x86)");
                switch (app.id()) {
                    case "notepad" -> paths.add(Path.of(systemRoot, "System32", "notepad.exe"));
                    case "firefox" -> {
                        paths.add(Path.of(programFiles, "Mozilla Firefox", "firefox.exe"));
                        paths.add(Path.of(programFilesX86, "Mozilla Firefox", "firefox.exe"));
                    }
                    case "chrome" -> {
                        paths.add(Path.of(programFiles, "Google", "Chrome", "Application", "chrome.exe"));
                        if (!local.isBlank()) paths.add(Path.of(local, "Google", "Chrome", "Application", "chrome.exe"));
                    }
                    case "edge" -> paths.add(Path.of(programFilesX86, "Microsoft", "Edge", "Application", "msedge.exe"));
                    case "obsidian" -> { if (!local.isBlank()) paths.add(Path.of(local, "Obsidian", "Obsidian.exe")); }
                    case "vscode" -> { if (!local.isBlank()) paths.add(Path.of(local, "Programs", "Microsoft VS Code", "Code.exe")); }
                    case "slack" -> {
                        if (!local.isBlank()) {
                            paths.add(Path.of(local, "slack", "slack.exe"));
                            paths.add(Path.of(local, "Programs", "slack", "slack.exe"));
                        }
                    }
                    default -> { }
                }
            } else {
                for (String name : app.processNames()) {
                    paths.add(Path.of("/usr/bin", name));
                    paths.add(Path.of("/usr/local/bin", name));
                }
            }
            return paths;
        }

        private static void startAndRequireSuccess(List<String> command) throws IOException {
            try {
                Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
                if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new IOException("desktop close adapter timed out");
                }
                if (process.exitValue() != 0) throw new IOException("desktop close adapter failed");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("desktop close adapter interrupted", e);
            }
        }

        private static boolean matches(ProcessHandle process, App app) {
            String command = process.info().command().orElse("");
            if (command.isBlank()) return false;
            String file = Path.of(command).getFileName().toString().toLowerCase(Locale.ROOT);
            return app.processNames().stream().map(name -> name.toLowerCase(Locale.ROOT))
                    .anyMatch(file::equals);
        }

        private static String windowsProcessName(App app) {
            String value = app.processNames().stream().filter(name -> name.endsWith(".exe"))
                    .findFirst().orElse(app.id());
            return value.substring(0, value.length() - (value.endsWith(".exe") ? 4 : 0));
        }
        private boolean windows() { return os.contains("win"); }
        private boolean mac() { return os.contains("mac"); }
    }

    record App(String id, String label, List<String> processNames) {}
    private record Request(App app, String action) {}
}
