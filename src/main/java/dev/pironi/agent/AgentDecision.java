package dev.pironi.agent;

import java.util.List;

public record AgentDecision(
        String thought,
        List<ToolCall> toolCalls,
        String finalAnswer
) {
    public AgentDecision {
        thought = thought == null ? "" : thought;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public boolean isFinished() {
        return finalAnswer != null && !finalAnswer.isBlank();
    }
}
