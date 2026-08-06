package dev.pironi.agent;

public record AgentResult(boolean success, String output, int turns, boolean streamed) {
    public AgentResult(boolean success, String output, int turns) {
        this(success, output, turns, false);
    }
}
