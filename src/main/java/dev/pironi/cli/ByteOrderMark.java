package dev.pironi.cli;

/**
 * Removes a leading byte order mark, which {@code String.strip()} leaves in place.
 *
 * <p>U+FEFF is not whitespace, so a first line that carries one no longer starts with "/" and a
 * slash command is spent on the model instead: {@code /doctor} came back as an offer to write a
 * clinical note. Windows produces one readily - PowerShell puts it at the head of a redirected
 * stream, and Set-Content and Notepad put it at the head of a file - so both the shell's input
 * and {@code --task-file} strip it.
 */
final class ByteOrderMark {
    private static final char MARK = '﻿';

    private ByteOrderMark() {
    }

    static String stripped(String text) {
        if (text == null || text.isEmpty() || text.charAt(0) != MARK) return text;
        return text.substring(1);
    }
}
