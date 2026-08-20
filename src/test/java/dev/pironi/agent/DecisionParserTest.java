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
                  "finding":"probe","toolCalls":[{"name":"read_file","arguments":{"path":"README.md"}}],
                  "finalAnswer":null
                }
                """);

        assertEquals(1, decision.toolCalls().size());
        assertEquals("read_file", decision.toolCalls().getFirst().name());
        assertEquals("README.md", decision.toolCalls().getFirst().arguments().path("path").asText());
    }

    @Test
    void rebuildsAMissingClosingBrace() throws Exception {
        // Run 19 turn 4: the tool call object never closed, so the array closer landed first.
        String broken = """
                {"thought":"t","finding":"f","toolCalls":[
                  {"name":"write_file","arguments":{"path":"a.sh","content":"x"}
                ]}
                """;

        String balanced = parser.withBalancedClosers(broken);

        AgentDecision decision = parser.parse(balanced);
        assertEquals(1, decision.toolCalls().size());
        assertEquals("write_file", decision.toolCalls().getFirst().name());
        assertEquals("x", decision.toolCalls().getFirst().arguments().path("content").asText());
    }

    @Test
    void correctsACloserOfTheWrongKind() throws Exception {
        // Run 19 turn 11: a brace stood where the array's bracket belonged.
        String broken = """
                {"thought":"t","finding":"f","toolCalls":[
                  {"name":"run_command","arguments":{"command":"ls","timeoutSeconds":120}}
                }
                """;

        String balanced = parser.withBalancedClosers(broken);

        AgentDecision decision = parser.parse(balanced);
        assertEquals(1, decision.toolCalls().size());
        assertEquals("ls", decision.toolCalls().getFirst().arguments().path("command").asText());
    }

    @Test
    void leavesBracesInsideStringsAlone() {
        String content = """
                {"thought":"t","toolCalls":[{"name":"run_command",
                  "arguments":{"command":"awk '{print $1}' f | sed 's/]//'"}}],"finalAnswer":null}
                """;

        assertEquals("", parser.withBalancedClosers(content));
    }

    @Test
    void refusesToGuessAtAnythingButClosers() {
        // A stray closer with nothing open is a different mistake, and inventing a tool call
        // out of it would run something the model never asked for.
        assertEquals("", parser.withBalancedClosers("{\"thought\":\"t\"}}"));
        // Cut off inside a string: what is missing is content, which cannot be reconstructed.
        assertEquals("", parser.withBalancedClosers("{\"thought\":\"unfinished"));
        assertEquals("", parser.withBalancedClosers(""));
    }

    @Test
    void refusesToFinishAResponseThatWasCutShort() {
        // A truncated finalAnswer would be published as if the model had finished the sentence.
        // Ending in the middle of a value is what tells the two apart from a misplaced closer.
        assertEquals("", parser.withBalancedClosers(
                "{\"thought\":\"cut\",\"toolCalls\":[],\"finalAnswer\":\"visible too early\""));
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

    @Test
    void keepsAnActingResponseThatOmittedTheFinding() throws Exception {
        AgentDecision decision = new DecisionParser(new ObjectMapper()).parse(
                "{\"thought\":\"probe\",\"toolCalls\":"
                        + "[{\"name\":\"read_file\",\"arguments\":{}}],\"finalAnswer\":null}"
        );
        org.junit.jupiter.api.Assertions.assertEquals("", decision.finding());
        org.junit.jupiter.api.Assertions.assertEquals(1, decision.toolCalls().size());
    }

    @Test
    void acceptsAFinishingResponseWithoutAFinding() throws Exception {
        AgentDecision decision = new DecisionParser(new ObjectMapper()).parse(
                "{\"thought\":\"done\",\"toolCalls\":[],\"finalAnswer\":\"ready\"}"
        );
        org.junit.jupiter.api.Assertions.assertEquals("", decision.finding());
        org.junit.jupiter.api.Assertions.assertEquals("ready", decision.finalAnswer());
    }

    @Test
    void namesTrailingContentThatJacksonWouldHaveDropped() throws Exception {
        DecisionParser parser = new DecisionParser(new ObjectMapper());
        String withStrayBrace =
                "{\"thought\":\"probe\",\"finding\":\"f\",\"toolCalls\":[],"
                        + "\"finalAnswer\":\"ready\"}}";

        // It still parses - Jackson reads the first value and drops the rest - but a run that
        // silently tolerates malformed output leaves no sign it happened.
        org.junit.jupiter.api.Assertions.assertEquals("ready",
                parser.parse(withStrayBrace).finalAnswer());
        org.junit.jupiter.api.Assertions.assertTrue(
                parser.trailingContent(withStrayBrace).contains("trailing content"),
                parser.trailingContent(withStrayBrace));
        org.junit.jupiter.api.Assertions.assertEquals("", parser.trailingContent(
                "{\"thought\":\"probe\",\"toolCalls\":[],\"finalAnswer\":\"ready\"}"));
    }
}
