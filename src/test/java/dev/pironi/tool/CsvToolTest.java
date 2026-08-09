package dev.pironi.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.pironi.safety.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsvToolTest {
    @TempDir Path root;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void mergesQuotedCsvAndLaterDuplicateWins() throws Exception {
        Files.writeString(root.resolve("a.csv"), "id,title\r\n1,Old\r\n2,Keep\r\n", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("b.csv"), "id,title\r\n1,\"New, quoted\"\r\n", StandardCharsets.UTF_8);
        ToolResult result = new CsvTool(new Workspace(root), CsvTool.Operation.MERGE).execute(mapper.readTree("""
                {"inputs":["a.csv","b.csv"],"output":"out/merged.csv","key":"id"}
                """));
        assertTrue(result.success(), result.output());
        String output = Files.readString(root.resolve("out/merged.csv"));
        assertTrue(output.contains("\"New, quoted\""));
        assertEquals(1, output.split("1,", -1).length - 1);
    }

    @Test void sanitizesFormulaCellsWithoutLosingUnicode() throws Exception {
        Files.writeString(root.resolve("unsafe.csv"), "name,value\r\nАнна,=HYPERLINK(\"\"x\"\")\r\nDaria Müller,@cmd\r\n", StandardCharsets.UTF_8);
        ToolResult result = new CsvTool(new Workspace(root), CsvTool.Operation.SANITIZE).execute(mapper.readTree("""
                {"input":"unsafe.csv","output":"safe.csv"}
                """));
        assertTrue(result.success(), result.output());
        String output = Files.readString(root.resolve("safe.csv"));
        assertTrue(output.contains("'=HYPERLINK"));
        assertTrue(output.contains("'@cmd"));
        assertTrue(output.contains("Анна"));
    }
}
