package dev.pironi.agent;

import com.fasterxml.jackson.databind.JsonNode;
import dev.pironi.model.ChatMessage;
import dev.pironi.model.ModelResponse;

import java.util.List;

/** Optional persistence, compression and skill state used by the agent loop. */
public interface AgentMemory {
    default List<ChatMessage> begin(String task) { return List.of(); }
    default void record(ChatMessage message, long promptTokens, long outputTokens) {}
    default void recordTool(String name, JsonNode arguments, String output) {}
    default void addTokens(ModelResponse response) {}
    default boolean shouldCompress() { return false; }
    default String compressionPrompt(List<ChatMessage> messages, String task) { return null; }
    default String storeSummary(String summary) { return summary; }
    default void checkpoint(List<ChatMessage> messages, String task) {}
    default String promptContext() { return ""; }
    default String activeSkillName() { return ""; }
    /** Outcome of skill selection for the current task: reason first, then per-skill scores. */
    default List<String> lastSkillDecision() { return List.of(); }
    /** What earlier runs against this workspace established. */
    default List<String> priorFindings() { return List.of(); }
    /** Hands this run's findings to the next one. */
    default void rememberFindings(List<String> findings) {}
    default void completed(String task, String answer) {}
    default void finished(boolean success) {}

    static AgentMemory none() { return new AgentMemory() {}; }
}
