package dev.pironi.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AgentResponseSchemaTest {
    @Test
    void definesStrictAgentEnvelope() {
        var schema = AgentResponseSchema.schema(new ObjectMapper());

        assertEquals("object", schema.path("type").asText());
        assertFalse(schema.path("additionalProperties").asBoolean());
        assertEquals(
                List.of("thought", "finding", "toolCalls", "finalAnswer"),
                new ObjectMapper().convertValue(schema.path("required"), List.class)
        );
        assertEquals(
                "object",
                schema.path("properties").path("toolCalls").path("items").path("type").asText()
        );
        // required, so constrained decoding emits it instead of silently dropping it
        assertEquals(
                "string",
                schema.path("properties").path("finding").path("type").asText()
        );
    }
}
