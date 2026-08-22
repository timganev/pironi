package dev.pironi.tool;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadLevelDbToolTest {

    @Test
    void leavesAJsonPayloadReadable() {
        byte[] value = "{\"subject\":\"stand-up\"}".getBytes(StandardCharsets.UTF_8);

        assertEquals("{\"subject\":\"stand-up\"}", ReadLevelDbTool.readable(value));
    }

    @Test
    void decodesATwoByteStringInsteadOfSpellingItOut() {
        // V8 stores anything outside Latin-1 as UTF-16, so a Bulgarian subject line arrives as
        // alternating bytes and used to come out as one letter per dot.
        byte[] value = "Среща с клиента".getBytes(StandardCharsets.UTF_16LE);

        assertEquals("Среща с клиента", ReadLevelDbTool.readable(value));
    }

    @Test
    void doesNotMistakeAsciiForATwoByteString() {
        // The test that separates the two is the high byte: in real UTF-16 text it is 0x04 for
        // Cyrillic and 0x00 for Latin, while a pair of ASCII bytes has 0x20 or more there.
        byte[] value = "meetingSubject".getBytes(StandardCharsets.US_ASCII);

        assertEquals("meetingSubject", ReadLevelDbTool.readable(value));
    }

    @Test
    void keepsFieldsFromRunningTogetherAcrossTheTypeTags() {
        byte[] value = {'"', 'a', 'b', 'c', 0x03, 0x00, '"', 'd', 'e', 'f'};

        String rendered = ReadLevelDbTool.readable(value);

        assertTrue(rendered.startsWith("\"abc"), rendered);
        assertTrue(rendered.endsWith("\"def"), rendered);
        assertFalse(rendered.contains("abc\"def"), "the tag bytes must leave a mark: " + rendered);
    }

    @Test
    void saysWhatItReadsAndHowFarItReaches() {
        ReadLevelDbTool tool = new ReadLevelDbTool(null, java.util.List.of());

        assertEquals("read_leveldb", tool.name());
        assertFalse(tool.mutating());
        assertTrue(tool.description().contains("leveldb"));
    }
}
