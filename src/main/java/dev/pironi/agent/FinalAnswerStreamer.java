package dev.pironi.agent;

import java.util.function.Consumer;

final class FinalAnswerStreamer {
    private static final String FINAL_ANSWER_FIELD = "\"finalAnswer\"";

    private final Consumer<String> output;
    private final StringBuilder json = new StringBuilder();
    private int emittedCharacters;

    FinalAnswerStreamer(Consumer<String> output) {
        this.output = output;
    }

    void accept(String chunk) {
        json.append(chunk);
        String decoded = decodedPrefix(FINAL_ANSWER_FIELD);
        if (decoded.length() > emittedCharacters) {
            output.accept(decoded.substring(emittedCharacters));
            emittedCharacters = decoded.length();
        }
    }

    boolean emitted() {
        return emittedCharacters > 0;
    }

    private String decodedPrefix(String fieldName) {
        int field = json.indexOf(fieldName);
        if (field < 0) {
            return "";
        }
        int cursor = valueStart(field + fieldName.length());
        if (cursor < 0) return "";

        StringBuilder decoded = new StringBuilder();
        while (cursor < json.length()) {
            char current = json.charAt(cursor++);
            if (current == '"') break;
            if (current != '\\') {
                decoded.append(current);
                continue;
            }
            if (cursor >= json.length()) break;
            char escaped = json.charAt(cursor++);
            switch (escaped) {
                case '"', '\\', '/' -> decoded.append(escaped);
                case 'b' -> decoded.append('\b');
                case 'f' -> decoded.append('\f');
                case 'n' -> decoded.append('\n');
                case 'r' -> decoded.append('\r');
                case 't' -> decoded.append('\t');
                case 'u' -> {
                    if (cursor + 4 > json.length()) return decoded.toString();
                    try {
                        decoded.append((char) Integer.parseInt(json.substring(cursor, cursor + 4), 16));
                    } catch (NumberFormatException ignored) {
                        return decoded.toString();
                    }
                    cursor += 4;
                }
                default -> { return decoded.toString(); }
            }
        }
        return decoded.toString();
    }

    private int valueStart(int cursor) {
        while (cursor < json.length() && Character.isWhitespace(json.charAt(cursor))) cursor++;
        if (cursor >= json.length() || json.charAt(cursor++) != ':') return -1;
        while (cursor < json.length() && Character.isWhitespace(json.charAt(cursor))) cursor++;
        if (cursor >= json.length() || json.charAt(cursor++) != '"') return -1;
        return cursor;
    }
}
