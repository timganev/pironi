package dev.pironi.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DecisionParserTest {
    private final DecisionParser parser = new DecisionParser(new ObjectMapper());

    @Test
    void parsesFinalAnswer() throws Exception {
        AgentDecision decision = parser.parse("""
                {"thought":"done","toolCalls":[],"finalAnswer":"complete"}
                """);

        assertTrue(decision.isFinished());
        assertEquals("complete", decision.finalAnswer());
    }

    @Test
    void parsesToolCall() throws Exception {
        AgentDecision decision = parser.parse("""
                {
                  "thought":"inspect",
                  "toolCalls":[{"name":"read_file","arguments":{"path":"README.md"}}],
                  "finalAnswer":null
                }
                """);

        assertEquals(1, decision.toolCalls().size());
        assertEquals("read_file", decision.toolCalls().getFirst().name());
        assertEquals("README.md", decision.toolCalls().getFirst().arguments().path("path").asText());
    }

    @Test
    void rejectsMalformedJson() {
        ProtocolException error = assertThrows(
                ProtocolException.class,
                () -> parser.parse("```json\n{}\n```")
        );

        assertTrue(error.getMessage().startsWith("Malformed JSON:"));
    }

    @Test
    void identifiesUnexpectedEndOfInputAsTruncation() {
        ProtocolException error = assertThrows(
                ProtocolException.class,
                () -> parser.parse("{\"thought\":\"cut off\"")
        );
        assertTrue(error.getMessage().startsWith("Truncated JSON:"));
    }

    @Test
    void rejectsResponseWithoutActionOrAnswer() {
        assertThrows(
                ProtocolException.class,
                () -> parser.parse("""
                        {"thought":"unsure","toolCalls":[],"finalAnswer":null}
                        """)
        );
    }

    @Test
    void rejectsOversizedInputBeforeParsing() {
        ProtocolException error = assertThrows(
                ProtocolException.class,
                () -> parser.parse("x".repeat(256 * 1024 + 1))
        );
        assertTrue(error.getMessage().contains("exceeds"));
    }
}
