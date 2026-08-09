package dev.pironi.tool;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Read-only, bounded process inventory without exposing command lines or environment secrets. */
public final class ProcessInspectTool implements Tool {
    private static final List<String> SORTS = List.of("memory", "cpu", "uptime", "pid");
    private final Backend backend;

    public ProcessInspectTool() { this(new NativeBackend()); }
    ProcessInspectTool(Backend backend) { this.backend = backend; }

    @Override public String name() { return "process_inspect"; }
    @Override public String description() {
        return "List running processes with PID, executable name, resident memory, sampled current CPU, "
                + "accumulated CPU time, uptime, and responsiveness when the OS exposes it. Never returns command-line "
                + "arguments or environment values. Use before proposing any process termination.";
    }
    @Override public String argumentSchema() {
        return "{\"sortBy\":\"memory|cpu|uptime|pid\",\"limit\":1-20}";
    }
    @Override public boolean mutating() { return false; }

    @Override public ToolResult execute(JsonNode arguments) {
        try {
            String sort = ToolArguments.requiredText(arguments, "sortBy")
                    .strip().toLowerCase(Locale.ROOT);
            if (!SORTS.contains(sort)) return ToolResult.failure("Unsupported sortBy: " + sort);
            int limit = ToolArguments.optionalPositiveInt(arguments, "limit", 10, 20);
            List<Snapshot> snapshots = new ArrayList<>(backend.snapshots(sort));
            snapshots.sort(comparator(sort));
            StringBuilder output = new StringBuilder("sort=").append(sort)
                    .append("; visibleProcesses=").append(snapshots.size()).append('\n');
            snapshots.stream().limit(limit).forEach(process -> output.append(process.render()).append('\n'));
            return ToolResult.success(output.toString().stripTrailing());
        } catch (IllegalArgumentException e) {
            return ToolResult.failure(e.getMessage());
        } catch (Exception e) {
            return ToolResult.failure("Process inspection failed: " + e.getMessage());
        }
    }

    private static Comparator<Snapshot> comparator(String sort) {
        Comparator<Snapshot> comparator = switch (sort) {
            case "memory" -> Comparator.comparingLong(Snapshot::residentBytes);
            case "cpu" -> Comparator.comparingDouble(Snapshot::cpuPercent);
            case "uptime" -> Comparator.comparingLong(Snapshot::uptimeSeconds);
            case "pid" -> Comparator.comparingLong(Snapshot::pid).reversed();
            default -> throw new IllegalArgumentException("Unsupported sortBy: " + sort);
        };
        return sort.equals("pid") ? comparator : comparator.reversed();
    }

    interface Backend { List<Snapshot> snapshots(String sort) throws Exception; }

    record Snapshot(long pid, String name, long residentBytes, double cpuPercent, double cpuSeconds,
                    long uptimeSeconds, String responding) {
        String render() {
            String memory = residentBytes < 0 ? "unknown"
                    : String.format(Locale.ROOT, "%.1f MiB", residentBytes / 1048576.0);
            String cpu = cpuSeconds < 0 ? "unknown"
                    : String.format(Locale.ROOT, "%.1f s", cpuSeconds);
            String currentCpu = cpuPercent < 0 ? "unknown"
                    : String.format(Locale.ROOT, "%.1f%%", cpuPercent);
            return "pid=" + pid + "; name=" + safeName(name) + "; memory=" + memory
                    + "; cpuNow=" + currentCpu + "; cpuTotal=" + cpu
                    + "; uptime=" + Math.max(0, uptimeSeconds) + " s"
                    + (responding.isBlank() ? "" : "; responding=" + responding);
        }

        private static String safeName(String value) {
            if (value.chars().anyMatch(Character::isISOControl)) return "unknown";
            String file;
            try { file = Path.of(value).getFileName().toString(); }
            catch (RuntimeException e) { file = "unknown"; }
            String safe = file.replaceAll("[^\\p{L}\\p{N}._+ -]", "");
            if (safe.isBlank()) return "unknown";
            return safe.length() <= 100 ? safe : safe.substring(0, 100);
        }
    }

    static final class NativeBackend implements Backend {
        @Override public List<Snapshot> snapshots(String sort) throws InterruptedException {
            Map<Long, WindowsMetrics> windows = windowsMetrics();
            Map<Long, Long> cpuBefore = new HashMap<>();
            long sampleStarted = System.nanoTime();
            if (sort.equals("cpu")) {
                try (var processes = ProcessHandle.allProcesses()) {
                    processes.forEach(process -> process.info().totalCpuDuration().ifPresent(
                            duration -> cpuBefore.put(process.pid(), duration.toNanos())));
                }
                Thread.sleep(300);
            }
            long sampleNanos = Math.max(1, System.nanoTime() - sampleStarted);
            Instant now = Instant.now();
            List<Snapshot> result = new ArrayList<>();
            try (var processes = ProcessHandle.allProcesses()) {
                processes.forEach(process -> {
                    ProcessHandle.Info info = process.info();
                    String command = info.command().orElse("");
                    if (command.isBlank()) return;
                    long pid = process.pid();
                    WindowsMetrics metric = windows.get(pid);
                    long memory = metric != null ? metric.residentBytes() : linuxResidentBytes(pid);
                    double cpu = info.totalCpuDuration().map(NativeBackend::seconds).orElse(-1.0);
                    double cpuPercent = -1;
                    if (sort.equals("cpu") && cpuBefore.containsKey(pid)
                            && info.totalCpuDuration().isPresent()) {
                        long delta = Math.max(0, info.totalCpuDuration().get().toNanos()
                                - cpuBefore.get(pid));
                        cpuPercent = delta * 100.0 / sampleNanos;
                    }
                    long uptime = info.startInstant().map(start -> Duration.between(start, now).toSeconds())
                            .orElse(-1L);
                    result.add(new Snapshot(pid, command, memory, cpuPercent, cpu, uptime,
                            metric == null ? "" : metric.responding()));
                });
            }
            return List.copyOf(result);
        }

        private static double seconds(Duration duration) {
            return duration.toNanos() / 1_000_000_000.0;
        }

        private static long linuxResidentBytes(long pid) {
            if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux")) return -1;
            Path status = Path.of("/proc", Long.toString(pid), "status");
            try {
                for (String line : Files.readAllLines(status, StandardCharsets.UTF_8)) {
                    if (!line.startsWith("VmRSS:")) continue;
                    String digits = line.substring(6).replaceAll("[^0-9]", "");
                    return digits.isBlank() ? -1 : Long.parseLong(digits) * 1024L;
                }
            } catch (IOException | NumberFormatException ignored) { }
            return -1;
        }

        private static Map<Long, WindowsMetrics> windowsMetrics() {
            if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
                return Map.of();
            }
            String script = "Get-Process | ForEach-Object { "
                    + "[Console]::WriteLine(('{0}{3}{1}{3}{2}' -f "
                    + "$_.Id,$_.WorkingSet64,$_.Responding,[char]9)) }";
            Map<Long, WindowsMetrics> result = new HashMap<>();
            try {
                Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                        "-Command", script).redirectErrorStream(true).start();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String[] fields = line.split("\\t", -1);
                        if (fields.length != 3) continue;
                        try {
                            result.put(Long.parseLong(fields[0]), new WindowsMetrics(
                                    Long.parseLong(fields[1]), fields[2].toLowerCase(Locale.ROOT)));
                        } catch (NumberFormatException ignored) { }
                    }
                }
                process.waitFor();
            } catch (IOException | InterruptedException ignored) {
                if (Thread.currentThread().isInterrupted()) Thread.currentThread().interrupt();
            }
            return Map.copyOf(result);
        }
    }

    private record WindowsMetrics(long residentBytes, String responding) { }
}
