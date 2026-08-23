package dev.pironi.tool;

import com.fasterxml.jackson.databind.JsonNode;
import dev.pironi.safety.Workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** RFC-4180-style CSV merge and Excel formula-injection sanitization. */
public final class CsvTool implements Tool {
    public enum Operation { MERGE, SANITIZE }
    private final Workspace workspace;
    private final Operation operation;
    private final dev.pironi.safety.CheckpointManager checkpointManager;

    public CsvTool(Workspace workspace, Operation operation) {
        this(workspace, operation, null);
    }

    public CsvTool(Workspace workspace, Operation operation,
            dev.pironi.safety.CheckpointManager checkpointManager) {
        this.workspace = workspace;
        this.operation = operation;
        this.checkpointManager = checkpointManager;
    }
    @Override public String name() { return operation == Operation.MERGE ? "csv_merge" : "csv_sanitize"; }
    @Override public String description() { return operation == Operation.MERGE
            ? "Merge UTF-8 CSV files by header, optionally de-duplicating by a key (later files win)."
            : "Create an Excel-safe UTF-8 CSV by prefixing formula-like cells with an apostrophe."; }
    @Override public String argumentSchema() { return operation == Operation.MERGE
            ? "{\"inputs\":[\"relative.csv\"],\"output\":\"relative.csv\",\"key\":\"header, optional\"}"
            : "{\"input\":\"relative.csv\",\"output\":\"relative.csv\"}"; }
    @Override public boolean mutating() { return true; }

    @Override public ToolResult validate(JsonNode arguments) {
        try {
            workspace.validateForWriteCreatingParents(ToolArguments.requiredText(arguments, "output"));
            if (operation == Operation.MERGE) {
                JsonNode inputs = arguments.get("inputs");
                if (inputs == null || !inputs.isArray() || inputs.isEmpty()) throw new IllegalArgumentException("inputs must be a non-empty array");
                for (JsonNode input : inputs) workspace.resolveExisting(input.asText());
            } else workspace.resolveExisting(ToolArguments.requiredText(arguments, "input"));
            return ToolResult.success("validated");
        } catch (IllegalArgumentException | IOException e) { return ToolResult.failure(e); }
    }

    @Override public ToolResult execute(JsonNode arguments) {
        ToolResult validation = validate(arguments);
        if (!validation.success()) return validation;
        try {
            List<List<String>> result = operation == Operation.MERGE ? merge(arguments) : sanitize(arguments);
            Path output = workspace.resolveForWriteCreatingParents(arguments.path("output").asText());
            String checkpoint = SafeWrite.snapshot(checkpointManager, output);
            SafeWrite.write(output, render(result));
            List<List<String>> roundTrip = parse(Files.readString(output, StandardCharsets.UTF_8));
            if (!roundTrip.equals(result)) throw new IOException("CSV round-trip validation failed");
            return ToolResult.success("Created and validated " + workspace.root().relativize(output)
                    + " with " + Math.max(0, result.size() - 1) + " data rows"
                    + SafeWrite.checkpointNote(checkpoint));
        } catch (IOException | IllegalArgumentException e) { return ToolResult.failure(e); }
    }

    private List<List<String>> merge(JsonNode arguments) throws IOException {
        List<String> header = null;
        List<List<String>> rows = new ArrayList<>();
        for (JsonNode input : arguments.path("inputs")) {
            List<List<String>> csv = parse(Files.readString(workspace.resolveExisting(input.asText()), StandardCharsets.UTF_8));
            if (csv.isEmpty()) continue;
            if (header == null) header = csv.getFirst();
            else if (!header.equals(csv.getFirst())) throw new IllegalArgumentException("CSV headers differ: " + input.asText());
            rows.addAll(csv.subList(1, csv.size()));
        }
        if (header == null) throw new IllegalArgumentException("No CSV headers found");
        String key = arguments.path("key").asText("");
        if (!key.isBlank()) {
            int keyIndex = header.indexOf(key);
            if (keyIndex < 0) throw new IllegalArgumentException("Key header not found: " + key);
            LinkedHashMap<String, List<String>> deduplicated = new LinkedHashMap<>();
            for (List<String> row : rows) {
                // A short row is ordinary in an export, and row.get(keyIndex) threw
                // IndexOutOfBoundsException - which is not IllegalArgumentException, so it went
                // straight past execute()'s catch and out of the tool as a raw failure naming no
                // file and no row.
                if (keyIndex >= row.size()) {
                    throw new IllegalArgumentException(
                            "Row " + (rows.indexOf(row) + 1) + " has " + row.size()
                                    + " columns but the key '" + key + "' is column "
                                    + (keyIndex + 1) + "; deduplication needs every row to carry it");
                }
                deduplicated.put(row.get(keyIndex), row);
            }
            rows = new ArrayList<>(deduplicated.values());
        }
        List<List<String>> result = new ArrayList<>(); result.add(header); result.addAll(rows); return result;
    }

    private List<List<String>> sanitize(JsonNode arguments) throws IOException {
        List<List<String>> csv = parse(Files.readString(workspace.resolveExisting(arguments.path("input").asText()), StandardCharsets.UTF_8));
        List<List<String>> result = new ArrayList<>();
        for (List<String> row : csv) {
            List<String> safe = new ArrayList<>();
            for (String cell : row) {
                String stripped = cell.stripLeading();
                safe.add(!stripped.isEmpty() && "=+-@".indexOf(stripped.charAt(0)) >= 0 ? "'" + cell : cell);
            }
            result.add(safe);
        }
        return result;
    }

    static List<List<String>> parse(String input) {
        List<List<String>> rows = new ArrayList<>(); List<String> row = new ArrayList<>(); StringBuilder cell = new StringBuilder(); boolean quoted = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < input.length() && input.charAt(i + 1) == '"') { cell.append('"'); i++; }
                else if (c == '"') quoted = false; else cell.append(c);
            } else if (c == '"' && cell.isEmpty()) quoted = true;
            else if (c == ',') { row.add(cell.toString()); cell.setLength(0); }
            else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < input.length() && input.charAt(i + 1) == '\n') i++;
                row.add(cell.toString()); cell.setLength(0); rows.add(row); row = new ArrayList<>();
            } else cell.append(c);
        }
        if (!cell.isEmpty() || !row.isEmpty()) { row.add(cell.toString()); rows.add(row); }
        return rows;
    }
    static String render(List<List<String>> rows) {
        StringBuilder output = new StringBuilder();
        for (List<String> row : rows) {
            for (int i = 0; i < row.size(); i++) {
                if (i > 0) output.append(','); String cell = row.get(i);
                if (cell.indexOf(',') >= 0 || cell.indexOf('"') >= 0 || cell.indexOf('\n') >= 0 || cell.indexOf('\r') >= 0) output.append('"').append(cell.replace("\"", "\"\"")).append('"');
                else output.append(cell);
            }
            output.append("\r\n");
        }
        return output.toString();
    }
}
