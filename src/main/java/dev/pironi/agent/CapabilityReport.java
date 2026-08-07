package dev.pironi.agent;

import dev.pironi.tool.PlatformShell;
import dev.pironi.tool.ToolRegistry;

import java.util.stream.Collectors;

/** Authoritative capability manifest generated from the live tool registry. */
public final class CapabilityReport {
    private final ToolRegistry tools;
    private final AgentContext context;

    public CapabilityReport(ToolRegistry tools, AgentContext context) {
        this.tools = tools;
        this.context = context;
    }

    public String render() {
        String names = tools.all().stream().map(tool -> tool.name())
                .sorted().collect(Collectors.joining(", "));
        boolean shell = tools.find("run_command").isPresent();
        boolean http = tools.find("http_get").isPresent();
        String network = http
                ? "available through http_get"
                : shell ? "inherited through run_command; verify with a real request"
                : "no registered network-capable tool";
        return """
                platform: %s (%s)
                shell: %s
                network: %s
                tools: %s
                live configuration:
                %s
                """.formatted(
                System.getProperty("os.name", "unknown"),
                System.getProperty("os.arch", "unknown"),
                shell ? PlatformShell.name() : "unavailable",
                network,
                names.isBlank() ? "none" : names,
                context.runtimeSession().indent(2).stripTrailing()
        ).strip();
    }
}
