package dev.pironi.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ByteOrderMarkTest {
    private static final String MARK = "﻿";

    @Test
    void aLeadingMarkIsRemoved() {
        assertEquals("/doctor", ByteOrderMark.stripped(MARK + "/doctor"));
    }

    @Test
    void aCommandCarryingOneStartsWithASlashAgain() {
        // This is the property the shell dispatches on: strip() alone left the mark in place,
        // so the line did not start with "/" and the command was sent to the model.
        String line = MARK + "/doctor";
        assertTrue(!line.strip().startsWith("/"), "precondition: strip() does not remove it");
        assertTrue(ByteOrderMark.stripped(line).strip().startsWith("/"));
    }

    @Test
    void textWithoutOneIsUntouched() {
        assertEquals("/doctor", ByteOrderMark.stripped("/doctor"));
        assertEquals("", ByteOrderMark.stripped(""));
        assertNull(ByteOrderMark.stripped(null));
    }

    @Test
    void onlyTheFirstCharacterCounts() {
        // Elsewhere it is a zero-width no-break space and belongs to the text.
        assertEquals("a" + MARK + "b", ByteOrderMark.stripped("a" + MARK + "b"));
        assertEquals(MARK + "x", ByteOrderMark.stripped(MARK + MARK + "x"));
    }
}
