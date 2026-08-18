package dev.pironi.agent;

import java.util.List;

public record AgentDecision(
        String thought,
        String finding,
        List<ToolCall> toolCalls,
        String finalAnswer
) {
    public AgentDecision {
        thought = thought == null ? "" : thought;
        finding = finding == null ? "" : finding;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public boolean isFinished() {
        return finalAnswer != null && !finalAnswer.isBlank();
    }
}
