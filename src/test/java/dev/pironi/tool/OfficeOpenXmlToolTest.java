package dev.pironi.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.pironi.safety.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfficeOpenXmlToolTest {
    @TempDir Path root;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void createsValidDocxWithUnicode() throws Exception {
        ToolResult result = new OfficeOpenXmlTool(new Workspace(root), OfficeOpenXmlTool.Format.DOCX).execute(mapper.readTree("""
                {"path":"out/status.docx","title":"Phoenix Weekly Status","paragraphs":["Owner: Анна Петрова","Daria Müller"]}
                """));
        assertTrue(result.success(), result.output());
        try (ZipFile zip = new ZipFile(root.resolve("out/status.docx").toFile())) {
            assertNotNull(zip.getEntry("word/document.xml"));
            String xml = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(xml.contains("Анна Петрова"));
        }
    }

    @Test void createsWorkbookWithMultipleSheets() throws Exception {
        ToolResult result = new OfficeOpenXmlTool(new Workspace(root), OfficeOpenXmlTool.Format.XLSX).execute(mapper.readTree("""
                {"path":"out/status.xlsx","sheets":[{"name":"Summary","rows":[["Metric","Value"],["Done","2"]]},{"name":"Risks","rows":[["ID","Risk"],["R-1","Delay"]]}]}
                """));
        assertTrue(result.success(), result.output());
        try (ZipFile zip = new ZipFile(root.resolve("out/status.xlsx").toFile())) {
            assertNotNull(zip.getEntry("xl/worksheets/sheet1.xml"));
            assertNotNull(zip.getEntry("xl/worksheets/sheet2.xml"));
        }
    }

    @Test void createsPresentationWithExpectedSlides() throws Exception {
        ToolResult result = new OfficeOpenXmlTool(new Workspace(root), OfficeOpenXmlTool.Format.PPTX).execute(mapper.readTree("""
                {"path":"out/status.pptx","slides":[{"title":"Phoenix","bullets":["On track"]},{"title":"Risks","bullets":["Review delay"]}]}
                """));
        assertTrue(result.success(), result.output());
        try (ZipFile zip = new ZipFile(root.resolve("out/status.pptx").toFile())) {
            assertNotNull(zip.getEntry("ppt/slides/slide1.xml"));
            assertNotNull(zip.getEntry("ppt/slides/slide2.xml"));
        }
    }
}
