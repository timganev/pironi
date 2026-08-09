package dev.pironi.tool;

import com.fasterxml.jackson.databind.JsonNode;
import dev.pironi.safety.Workspace;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.FileStore;
import java.nio.file.Files;

/** Cross-platform, read-only hardware/runtime facts without shell commands. */
public final class SystemInfoTool implements Tool {
    private final Workspace workspace;
    public SystemInfoTool(Workspace workspace) { this.workspace = workspace; }
    @Override public String name() { return "system_info"; }
    @Override public String description() { return "Report measured cross-platform OS, architecture, logical processors, physical/JVM memory, workspace disk space, Java and shell facts without running a command."; }
    @Override public String argumentSchema() { return "{}"; }
    @Override public boolean mutating() { return false; }
    @Override public ToolResult execute(JsonNode arguments) {
        try {
            Runtime runtime = Runtime.getRuntime(); FileStore store = Files.getFileStore(workspace.root());
            long physicalTotal = -1, physicalFree = -1;
            var bean = ManagementFactory.getOperatingSystemMXBean();
            if (bean instanceof com.sun.management.OperatingSystemMXBean os) {
                physicalTotal = os.getTotalMemorySize(); physicalFree = os.getFreeMemorySize();
            }
            return ToolResult.success("os=" + System.getProperty("os.name") + "\narch=" + System.getProperty("os.arch")
                    + "\nlogicalProcessors=" + runtime.availableProcessors() + "\nphysicalMemoryBytes=" + value(physicalTotal)
                    + "\nfreePhysicalMemoryBytes=" + value(physicalFree) + "\njvmMaxHeapBytes=" + runtime.maxMemory()
                    + "\nworkspace=" + workspace.root() + "\nworkspaceUsableBytes=" + store.getUsableSpace()
                    + "\njava=" + System.getProperty("java.version") + " " + System.getProperty("java.vendor")
                    + "\nshell=" + PlatformShell.name());
        } catch (IOException e) { return ToolResult.failure(e.getMessage()); }
    }
    private static String value(long value) { return value < 0 ? "unknown" : Long.toString(value); }
}
