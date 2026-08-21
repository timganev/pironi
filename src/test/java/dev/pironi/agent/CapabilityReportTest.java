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
        assertTrue(report.contains("exposed tools: run_command"));
        assertTrue(report.contains("approval: auto"));
    }

    @Test void reportsNoNetworkToolInsteadOfOverclaiming() {
        String report = new CapabilityReport(
                new ToolRegistry(List.of()), new AgentContext("", "", "")
        ).render();
        assertTrue(report.contains("network: no registered network-capable tool"));
        assertTrue(report.contains("run_command: not implemented"));
    }

    @Test void distinguishesHostShellFromPolicyDisabledRunCommand() {
        AgentContext context = new AgentContext("", "", "");
        String report = new CapabilityReport(
                new ToolRegistry(List.of()), context,
                List.of("run_command", "http_get"),
                java.util.Map.of("run_command", "blocked by auto-safe workspace policy")
        ).render();
        assertTrue(report.contains("host shell: "));
        assertTrue(report.contains("run_command: implemented but not exposed"));
        assertTrue(report.contains("run_command — blocked by auto-safe workspace policy"));
    }

    @Test
    void namesTheSavedSkillsSoTheyAreNotSearchedForAsFiles() {
        // Asked to use a saved skill, an agent answered that no such mechanism existed and spent
        // sixteen seconds searching the project for a file that was never there.
        String report = new CapabilityReport(
                new ToolRegistry(java.util.List.of()), new AgentContext("", "", ""),
                java.util.List.of(), java.util.Map.of(),
                "top10 - Three-day forecast. They are chosen automatically."
        ).render();

        assertTrue(report.contains("skills:"), report);
        assertTrue(report.contains("top10"), report);
    }

    @Test
    void saysWhenNoSkillIsSaved() {
        String report = new CapabilityReport(
                new ToolRegistry(java.util.List.of()), new AgentContext("", "", "")).render();

        assertTrue(report.contains("skills: none saved"), report);
    }
}
