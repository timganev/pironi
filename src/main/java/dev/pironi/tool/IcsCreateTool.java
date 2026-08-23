package dev.pironi.tool;

import com.fasterxml.jackson.databind.JsonNode;
import dev.pironi.safety.Workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

public final class IcsCreateTool implements Tool {
    private final Workspace workspace;
    private final dev.pironi.safety.CheckpointManager checkpointManager;
    public IcsCreateTool(Workspace workspace) { this(workspace, null); }
    public IcsCreateTool(Workspace workspace, dev.pironi.safety.CheckpointManager checkpointManager) {
        this.workspace = workspace;
        this.checkpointManager = checkpointManager;
    }
    @Override public String name() { return "ics_create"; }
    @Override public String description() { return "Create and validate an iCalendar file without Outlook or sending invitations."; }
    @Override public String argumentSchema() { return "{\"path\":\"relative .ics\",\"events\":[{\"uid\":\"stable id\",\"startUtc\":\"yyyyMMddTHHmmssZ\",\"endUtc\":\"yyyyMMddTHHmmssZ\",\"summary\":\"text\",\"description\":\"text, optional\"}]}"; }
    @Override public boolean mutating() { return true; }
    @Override public ToolResult validate(JsonNode arguments) {
        try {
            String path = ToolArguments.requiredText(arguments, "path");
            if (!path.toLowerCase().endsWith(".ics")) throw new IllegalArgumentException("path must end with .ics");
            workspace.validateForWriteCreatingParents(path);
            JsonNode events = arguments.get("events");
            if (events == null || !events.isArray() || events.isEmpty()) throw new IllegalArgumentException("events must be a non-empty array");
            return ToolResult.success("validated");
        } catch (IllegalArgumentException | IOException e) { return ToolResult.failure(e); }
    }
    @Override public ToolResult execute(JsonNode arguments) {
        ToolResult validation = validate(arguments); if (!validation.success()) return validation;
        try {
            StringBuilder value = new StringBuilder("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Pironi//Team Lead//EN\r\nCALSCALE:GREGORIAN\r\n");
            Set<String> uids = new HashSet<>(); int count = 0;
            for (JsonNode event : arguments.path("events")) {
                String uid = required(event, "uid"); if (!uids.add(uid)) throw new IllegalArgumentException("Duplicate UID: " + uid);
                String start = date(required(event, "startUtc")); String end = date(required(event, "endUtc"));
                value.append("BEGIN:VEVENT\r\nUID:").append(escape(uid)).append("\r\nDTSTAMP:20260809T090000Z\r\nDTSTART:").append(start).append("\r\nDTEND:").append(end).append("\r\nSUMMARY:").append(escape(required(event, "summary"))).append("\r\n");
                if (event.hasNonNull("description")) value.append("DESCRIPTION:").append(escape(event.path("description").asText())).append("\r\n");
                value.append("END:VEVENT\r\n"); count++;
            }
            value.append("END:VCALENDAR\r\n");
            Path output = workspace.resolveForWriteCreatingParents(arguments.path("path").asText());
            String checkpoint = SafeWrite.snapshot(checkpointManager, output);
            SafeWrite.write(output, value.toString());
            String saved = Files.readString(output, StandardCharsets.UTF_8);
            if (occurrences(saved, "BEGIN:VEVENT") != count || !saved.endsWith("END:VCALENDAR\r\n")) throw new IOException("iCalendar validation failed");
            return ToolResult.success("Created and validated " + workspace.root().relativize(output)
                    + " with " + count + " events" + SafeWrite.checkpointNote(checkpoint));
        } catch (IOException | IllegalArgumentException e) { return ToolResult.failure(e); }
    }
    private static String required(JsonNode node, String field) { String value = node.path(field).asText(""); if (value.isBlank()) throw new IllegalArgumentException(field + " is required"); return value; }
    private static String date(String value) { if (!value.matches("\\d{8}T\\d{6}Z")) throw new IllegalArgumentException("UTC date must match yyyyMMddTHHmmssZ: " + value); return value; }
    private static String escape(String value) { return value.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\r\n", "\\n").replace("\n", "\\n"); }
    private static int occurrences(String value, String needle) { int count = 0, from = 0; while ((from = value.indexOf(needle, from)) >= 0) { count++; from += needle.length(); } return count; }
}
