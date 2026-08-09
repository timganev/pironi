package dev.pironi.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Map;
import java.util.regex.Pattern;

/** Small persistence-boundary redactor; it never changes live tool/model data. */
public final class SecretRedactor {
    private static final String MASK = "[REDACTED]";
    private static final Pattern ASSIGNMENT = Pattern.compile(
            "(?i)(\\b(?:[a-z0-9_]*(?:api_key|access_token|refresh_token|password|passwd|secret)"
                    + "|api[ _-]?key|access[ _-]?token|refresh[ _-]?token|password|passwd|secret"
                    + "|token)\\b\\s*[:=]\\s*[\"']?)([^\\s,\"'}]+)"
    );
    private static final Pattern BEARER = Pattern.compile(
            "(?i)(\\bbearer\\s+)[a-z0-9._~+/=-]+"
    );
    private static final Pattern QUOTED_ASSIGNMENT = Pattern.compile(
            "(?i)([\"'](?:api[_-]?key|access[_-]?token|refresh[_-]?token|password|passwd|"
                    + "secret|token|authorization|credential)[\"']\\s*:\\s*[\"'])([^\"']+)([\"'])"
    );
    private static final Pattern COMMON_KEY = Pattern.compile(
            "\\b(?:sk|pk|api)-[a-zA-Z0-9_-]{8,}\\b"
    );
    private static final Pattern SENSITIVE_FIELD = Pattern.compile(
            "(?i)(?:authorization|proxy-authorization|cookie|set-cookie|password|passwd|secret|"
                    + "token|access[_-]?token|refresh[_-]?token|api[_-]?key|credential)"
    );

    private SecretRedactor() {}

    public static String redact(String value) {
        if (value == null || value.isEmpty()) return value;
        String result = ASSIGNMENT.matcher(value).replaceAll("$1" + MASK);
        result = QUOTED_ASSIGNMENT.matcher(result).replaceAll("$1" + MASK + "$3");
        result = BEARER.matcher(result).replaceAll("$1" + MASK);
        return COMMON_KEY.matcher(result).replaceAll(MASK);
    }

    /** Returns a redacted copy, preserving the original JSON tree and its types. */
    public static JsonNode redact(JsonNode value) {
        if (value == null) return null;
        JsonNode copy = value.deepCopy();
        redactInPlace(copy);
        return copy;
    }

    private static void redactInPlace(JsonNode node) {
        if (node instanceof ObjectNode object) {
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode child = field.getValue();
                if (SENSITIVE_FIELD.matcher(field.getKey()).matches()) {
                    object.put(field.getKey(), MASK);
                } else if (child.isTextual()) {
                    object.put(field.getKey(), redact(child.textValue()));
                } else {
                    redactInPlace(child);
                }
            }
        } else if (node instanceof ArrayNode array) {
            for (int index = 0; index < array.size(); index++) {
                JsonNode child = array.get(index);
                if (child.isTextual()) array.set(index, array.textNode(redact(child.textValue())));
                else redactInPlace(child);
            }
        }
    }
}
