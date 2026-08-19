package dev.pironi.agent;

import java.util.List;

public record AgentDecision(
        String thought,
        String finding,
        String remember,
        List<ToolCall> toolCalls,
        String finalAnswer
) {
    /** Kept so the older four-argument form still constructs a decision that remembers nothing. */
    public AgentDecision(String thought, String finding, List<ToolCall> toolCalls,
            String finalAnswer) {
        this(thought, finding, "", toolCalls, finalAnswer);
    }

    public AgentDecision {
        thought = thought == null ? "" : thought;
        finding = finding == null ? "" : finding;
        remember = remember == null ? "" : remember;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public boolean isFinished() {
        return finalAnswer != null && !finalAnswer.isBlank();
    }
}
