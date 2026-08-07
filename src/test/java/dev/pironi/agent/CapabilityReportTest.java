package dev.pironi.agent;

import dev.pironi.tool.Tool;
import dev.pironi.tool.ToolRegistry;
import dev.pironi.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CapabilityReportTest {
    @Test void derivesCapabilitiesFromLiveToolsAndRuntimeContext() {
        AgentContext context = new AgentContext("", "", "");
        context.updateRuntimeSession("provider: deepseek\napproval: auto");
        Tool command = new Tool() {
            public String name() { return "run_command"; }
            public String description() { return "command"; }
            public String argumentSchema() { return "{}"; }
            public boolean mutating() { return true; }
            public ToolResult execute(JsonNode arguments) { return ToolResult.success("ok"); }
        };

        String report = new CapabilityReport(new ToolRegistry(List.of(command)), context).render();

        assertTrue(report.contains("network: inherited through run_command"));
        assertTrue(report.contains("tools: run_command"));
        assertTrue(report.contains("approval: auto"));
    }

    @Test void reportsNoNetworkToolInsteadOfOverclaiming() {
        String report = new CapabilityReport(
                new ToolRegistry(List.of()), new AgentContext("", "", "")
        ).render();
        assertTrue(report.contains("network: no registered network-capable tool"));
        assertTrue(report.contains("shell: unavailable"));
    }
}
