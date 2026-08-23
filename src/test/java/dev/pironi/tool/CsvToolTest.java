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

    /**
     * A short row is ordinary in an export. {@code row.get(keyIndex)} threw
     * IndexOutOfBoundsException, which is not IllegalArgumentException, so it went straight past
     * execute()'s catch and left the tool as a raw exception naming no file and no row.
     */
    @Test void aRowShorterThanTheKeyColumnIsAFailureAndNotACrash() throws Exception {
        // The key is not the first column, which is the only reason this ever reached get().
        Files.writeString(root.resolve("a.csv"), "id,email\r\n1,ada@example.com\r\n2\r\n",
                StandardCharsets.UTF_8);

        ToolResult result = new CsvTool(new Workspace(root), CsvTool.Operation.MERGE)
                .execute(mapper.readTree("""
                        {"inputs":["a.csv"],"output":"out.csv","key":"email"}
                        """));

        assertTrue(!result.success(), result.output());
        assertTrue(result.output().contains("columns"), result.output());
    }

    /**
     * csv_sanitize pointed at a file that already exists destroyed it with nothing to roll back
     * to, while write_file and apply_patch beside it had snapshotted before every overwrite.
     */
    @Test void overwritingAnExistingFileIsUndoable() throws Exception {
        Files.writeString(root.resolve("in.csv"), "id\r\n1\r\n", StandardCharsets.UTF_8);
        Path output = root.resolve("out.csv");
        Files.writeString(output, "irreplaceable", StandardCharsets.UTF_8);
        Workspace workspace = new Workspace(root);
        var checkpoints = new dev.pironi.safety.CheckpointManager(workspace);

        ToolResult result = new CsvTool(workspace, CsvTool.Operation.SANITIZE, checkpoints)
                .execute(mapper.readTree("""
                        {"input":"in.csv","output":"out.csv"}
                        """));

        assertTrue(result.success(), result.output());
        assertTrue(result.output().contains("checkpoint="), result.output());
        checkpoints.rollbackLatest();
        assertEquals("irreplaceable", Files.readString(output, StandardCharsets.UTF_8));
    }
}
