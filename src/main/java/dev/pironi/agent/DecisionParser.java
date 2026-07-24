package dev.pironi.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

public final class DecisionParser {
    private static final int MAX_RAW_CONTENT = 256 * 1024;
    private final ObjectMapper objectMapper;

    public DecisionParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public AgentDecision parse(String rawContent) throws ProtocolException {
        if (rawContent == null || rawContent.isBlank()) {
            throw new ProtocolException("Model returned empty content");
        }
        if (rawContent.length() > MAX_RAW_CONTENT) {
            throw new ProtocolException(
                    "Model response exceeds " + MAX_RAW_CONTENT + " characters"
            );
        }

        try {
            JsonNode root = objectMapper.readTree(rawContent);
            if (!root.isObject()) {
                throw new ProtocolException("Model response must be a JSON object");
            }

            String thought = root.path("thought").asText("");
            String finalAnswer = textOrNull(root.get("finalAnswer"));
            List<ToolCall> calls = parseToolCalls(root.get("toolCalls"));

            if ((finalAnswer == null || finalAnswer.isBlank()) && calls.isEmpty()) {
                throw new ProtocolException("Response must contain finalAnswer or at least one tool call");
            }
            return new AgentDecision(thought, calls, finalAnswer);
        } catch (JsonProcessingException e) {
            throw new ProtocolException("Malformed JSON: " + e.getOriginalMessage(), e);
        }
    }

    private List<ToolCall> parseToolCalls(JsonNode node) throws ProtocolException {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new ProtocolException("toolCalls must be an array");
        }

        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isObject()) {
                throw new ProtocolException("Each tool call must be an object");
            }
            String name = item.path("name").asText("");
            if (name.isBlank()) {
                throw new ProtocolException("Tool call name must not be blank");
            }
            JsonNode arguments = item.get("arguments");
            if (arguments == null || !arguments.isObject()) {
                throw new ProtocolException("Tool call arguments for " + name + " must be an object");
            }
            calls.add(new ToolCall(name, arguments));
        }
        return List.copyOf(calls);
    }

    private static String textOrNull(JsonNode node) throws ProtocolException {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw new ProtocolException("finalAnswer must be a string or null");
        }
        return node.textValue();
    }
}
