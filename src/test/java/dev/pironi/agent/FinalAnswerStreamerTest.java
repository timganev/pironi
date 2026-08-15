package dev.pironi.agent;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FinalAnswerStreamerTest {
    @Test void writesValidatedAnswerInFlushedChunksWithoutChangingUnicode() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        List<Long> pauses = new ArrayList<>();
        try (PrintStream output = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            FinalAnswerStreamer streamer = new FinalAnswerStreamer(output, null, pauses::add);
            streamer.accept("Здравей, Daria Müller. Готово.");
            streamer.accept(System.lineSeparator());
        }
        assertEquals("Здравей, Daria Müller. Готово." + System.lineSeparator(),
                bytes.toString(StandardCharsets.UTF_8));
        assertTrue(pauses.size() >= 3);
        assertTrue(pauses.stream().allMatch(delay -> delay >= 8 && delay <= 35));
    }

    @Test
    void multiLineAnswersUseCarriageReturnsForTheTerminal() {
        // Without CR the second line starts under the end of the first, and a twelve line
        // answer walks diagonally off the right edge - visible on Windows terminals.
        assertEquals("one\r\ntwo", FinalAnswerStreamer.crlf("one\ntwo"));
        assertEquals("one\r\ntwo", FinalAnswerStreamer.crlf("one\r\ntwo"),
                "already-normalised text must not gain a second carriage return");
        assertEquals("no newlines here", FinalAnswerStreamer.crlf("no newlines here"));
        assertEquals("a\r\nb\r\nc", FinalAnswerStreamer.crlf("a\nb\nc"));
    }
}
