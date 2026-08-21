package dev.pironi.tool;

import com.fasterxml.jackson.databind.JsonNode;

final class ToolArguments {
    private ToolArguments() {
    }

    static String requiredText(JsonNode arguments, String field) {
        JsonNode value = arguments.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException(field + " must be a non-blank string");
        }
        return value.textValue();
    }

    static int optionalPositiveInt(JsonNode arguments, String field, int defaultValue, int maxValue) {
        JsonNode value = arguments.get(field);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        if (!value.canConvertToInt()) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        int result = value.intValue();
        if (result <= 0 || result > maxValue) {
            throw new IllegalArgumentException(field + " must be between 1 and " + maxValue);
        }
        return result;
    }

    /** Returns the value of an optional object field, or {@code null} when absent/null. */
    static JsonNode optionalObject(JsonNode arguments, String field) {
        JsonNode value = arguments.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isObject()) {
            throw new IllegalArgumentException(field + " must be an object of header-name to header-value pairs");
        }
        return value;
    }
}
