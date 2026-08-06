package dev.pironi.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FinalAnswerStreamerTest {
    @Test
    void streamsOnlyFinalAnswerAcrossArbitraryChunks() {
        StringBuilder output = new StringBuilder();
        FinalAnswerStreamer streamer = new FinalAnswerStreamer(output::append);

        streamer.accept("{\"thought\":\"hidden\",\"toolCalls\":[],\"final");
        assertEquals("", output.toString());
        streamer.accept("Answer\":\"Здра");
        streamer.accept("вей\\nсвят\"}");

        assertEquals("Здравей\nсвят", output.toString());
    }

    @Test
    void waitsForCompleteUnicodeEscape() {
        StringBuilder output = new StringBuilder();
        FinalAnswerStreamer streamer = new FinalAnswerStreamer(output::append);

        streamer.accept("{\"finalAnswer\":\"A\\u0");
        assertEquals("A", output.toString());
        streamer.accept("411\"}");

        assertEquals("AБ", output.toString());
    }

    @Test
    void ignoresNullFinalAnswerForToolTurns() {
        StringBuilder output = new StringBuilder();
        FinalAnswerStreamer streamer = new FinalAnswerStreamer(output::append);

        streamer.accept("{\"thought\":\"call\",\"toolCalls\":[{}],\"finalAnswer\":null}");

        assertEquals("", output.toString());
    }
}
