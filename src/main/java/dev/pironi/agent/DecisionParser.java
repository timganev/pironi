package dev.pironi.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
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
            String finding = root.path("finding").asText("");
            String remember = root.path("remember").asText("");
            String finalAnswer = textOrNull(root.get("finalAnswer"));
            List<ToolCall> calls = parseToolCalls(root.get("toolCalls"));

            if ((finalAnswer == null || finalAnswer.isBlank()) && calls.isEmpty()) {
                throw new ProtocolException("Response must contain finalAnswer or at least one tool call");
            }
            return new AgentDecision(thought, finding, remember, calls, finalAnswer);
        } catch (JsonProcessingException e) {
            String message = e.getOriginalMessage();
            String category = message != null && (
                    message.contains("end-of-input") || message.contains("Unexpected EOF")
            ) ? "Truncated JSON: " : "Malformed JSON: ";
            throw new ProtocolException(category + message, e);
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

    /**
     * Constrained decoding is supposed to make unbalanced JSON impossible, and yet this model
     * still emits it: a missing closing brace, or a brace standing where a bracket belongs.
     * Rebuilding the closers costs nothing, while asking for the response again costs a whole
     * turn. Only the punctuation is rebuilt, never content, and anything that cannot be
     * explained by a wrong or missing closer is left alone.
     *
     * @return the response with its closers corrected, or empty when nothing can be corrected
     */
    public String withBalancedClosers(String rawContent) {
        if (rawContent == null || rawContent.isBlank()) return "";
        // A response cut short ends in the middle of a value, and its finalAnswer may be half
        // written; one that merely misplaced a closer still ends by trying to close. Supplying
        // the punctuation for a truncated answer would publish a sentence the model never
        // finished, so only a response that reached its own end is repaired.
        String trimmed = rawContent.stripTrailing();
        if (!trimmed.endsWith("}") && !trimmed.endsWith("]")) return "";
        StringBuilder repaired = new StringBuilder(rawContent.length() + 8);
        Deque<Character> open = new ArrayDeque<>();
        boolean changed = false;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < rawContent.length(); i++) {
            char c = rawContent.charAt(i);
            if (inString) {
                repaired.append(c);
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') inString = false;
                continue;
            }
            switch (c) {
                case '"' -> {
                    inString = true;
                    repaired.append(c);
                }
                case '{', '[' -> {
                    open.push(c);
                    repaired.append(c);
                }
                case '}', ']' -> {
                    // More closers than openers is not a missing brace but a different mistake,
                    // and guessing at it would risk running a tool call we invented.
                    if (open.isEmpty()) return "";
                    char expected = open.pop() == '{' ? '}' : ']';
                    if (expected != c) changed = true;
                    repaired.append(expected);
                }
                default -> repaired.append(c);
            }
        }
        // Cut off inside a string means the missing part is content, which we cannot supply.
        if (inString) return "";
        while (!open.isEmpty()) {
            repaired.append(open.pop() == '{' ? '}' : ']');
            changed = true;
        }
        return changed ? repaired.toString() : "";
    }

    /**
     * Jackson reads the first value and drops whatever follows it, so a response with a stray
     * closing brace parses and runs as if nothing happened. Constrained decoding should make that
     * impossible, and when it does happen it is worth seeing rather than tolerating in silence.
     *
     * @return a description of the trailing content, or empty when the response is clean
     */
    public String trailingContent(String rawContent) {
        if (rawContent == null || rawContent.isBlank()) return "";
        try {
            objectMapper.readerFor(JsonNode.class)
                    .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readValue(rawContent);
            return "";
        } catch (JsonProcessingException e) {
            String message = e.getOriginalMessage();
            return "trailing content after the JSON object: "
                    + (message == null ? "unparsed remainder" : message);
        } catch (java.io.IOException e) {
            return "";
        }
    }
}
