package dev.pironi.agent;

import dev.pironi.tool.PlatformShell;
import dev.pironi.tool.ToolRegistry;

import java.util.stream.Collectors;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Authoritative capability manifest generated from the live tool registry. */
public final class CapabilityReport {
    private final ToolRegistry tools;
    private final AgentContext context;
    private final Set<String> implementedTools;
    private final Map<String, String> disabledReasons;
    private final String skills;

    public CapabilityReport(ToolRegistry tools, AgentContext context) {
        this(tools, context, tools.all().stream().map(tool -> tool.name()).toList(), Map.of(), "");
    }

    public CapabilityReport(ToolRegistry tools, AgentContext context,
            Collection<String> implementedTools, Map<String, String> disabledReasons) {
        this(tools, context, implementedTools, disabledReasons, "");
    }

    public CapabilityReport(ToolRegistry tools, AgentContext context,
            Collection<String> implementedTools, Map<String, String> disabledReasons,
            String skills) {
        this.tools = tools;
        this.context = context;
        this.implementedTools = Set.copyOf(implementedTools);
        this.disabledReasons = Map.copyOf(disabledReasons);
        this.skills = skills == null ? "" : skills;
    }

    /** Which files the identity came from, and that they are not yours to write. */
    private String personalContext() {
        String sources = context.personalSources();
        if (sources.isBlank()) {
            return "no SOUL.md or USER.md was loaded";
        }
        return "loaded from the following, in this order - they sit outside the workspace and "
                + "no tool here can write them; the user edits them directly:\n"
                + sources.indent(2).stripTrailing();
    }

    public String render() {
        String names = tools.all().stream().map(tool -> tool.name())
                .sorted().collect(Collectors.joining(", "));
        boolean shellExposed = tools.find("run_command").isPresent();
        boolean shellImplemented = implementedTools.contains("run_command");
        boolean http = tools.find("http_get").isPresent();
        boolean speed = tools.find("network_speed").isPresent();
        boolean desktop = tools.find("app_control").isPresent();
        boolean processInspect = tools.find("process_inspect").isPresent();
        boolean processControl = tools.find("process_control").isPresent();
        String network = speed
                ? "available; throughput measurement through network_speed"
                : http ? "available through http_get; throughput measurement unavailable"
                : shellExposed ? "inherited through run_command; verify with a real request"
                : "no registered network-capable tool";
        TreeSet<String> disabled = new TreeSet<>(implementedTools);
        tools.all().forEach(tool -> disabled.remove(tool.name()));
        String disabledText = disabled.isEmpty() ? "none" : disabled.stream()
                .map(name -> name + " — " + disabledReasons.getOrDefault(
                        name, "disabled by current approval/tool policy"))
                .collect(Collectors.joining("\n  ", "\n  ", ""));
        return """
                platform: %s (%s)
                host shell: %s
                run_command: %s
                network: %s
                desktop applications: %s
                process diagnostics: %s
                process termination: %s
                exposed tools: %s
                policy-disabled tools: %s
                personal context: %s
                skills: %s
                live configuration:
                %s
                """.formatted(
                System.getProperty("os.name", "unknown"),
                System.getProperty("os.arch", "unknown"),
                PlatformShell.name(),
                shellExposed ? "exposed as a tool"
                        : shellImplemented ? "implemented but not exposed; see policy-disabled tools"
                        : "not implemented",
                network,
                desktop ? "available through allowlisted app_control"
                        : "no registered desktop application tool",
                processInspect ? "available through process_inspect"
                        : "no registered process inspection tool",
                processControl ? "explicit approval required for every process_control action"
                        : "no registered process control tool",
                names.isBlank() ? "none" : names,
                disabledText,
                personalContext(),
                skills.isBlank() ? "none saved" : skills,
                context.runtimeSession().indent(2).stripTrailing()
        ).strip();
    }
}
