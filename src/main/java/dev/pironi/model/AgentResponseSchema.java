package dev.pironi.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Canonical structured-output schema shared by model providers. */
public final class AgentResponseSchema {
    private AgentResponseSchema() {
    }

    public static ObjectNode schema(ObjectMapper mapper) {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "object");
        root.put("additionalProperties", false);
        ArrayNode required = root.putArray("required");
        required.add("thought").add("toolCalls").add("finalAnswer");

        ObjectNode properties = root.putObject("properties");
        properties.putObject("thought").put("type", "string");

        ObjectNode toolCalls = properties.putObject("toolCalls");
        toolCalls.put("type", "array");
        ObjectNode call = toolCalls.putObject("items");
        call.put("type", "object");
        call.put("additionalProperties", false);
        call.putArray("required").add("name").add("arguments");
        ObjectNode callProperties = call.putObject("properties");
        callProperties.putObject("name").put("type", "string").put("minLength", 1);
        callProperties.putObject("arguments").put("type", "object");

        ObjectNode finalAnswer = properties.putObject("finalAnswer");
        finalAnswer.putArray("type").add("string").add("null");
        return root;
    }

    public static ObjectNode openAiResponseFormat(ObjectMapper mapper) {
        ObjectNode format = mapper.createObjectNode();
        format.put("type", "json_schema");
        ObjectNode definition = format.putObject("json_schema");
        definition.put("name", "pironi_agent_response");
        definition.put("strict", true);
        definition.set("schema", schema(mapper));
        return format;
    }
}
