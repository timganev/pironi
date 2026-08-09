package dev.pironi.tool;

import com.fasterxml.jackson.databind.JsonNode;
import dev.pironi.safety.Workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Creates small, dependency-free Office Open XML artifacts from structured content. */
public final class OfficeOpenXmlTool implements Tool {
    public enum Format { XLSX, DOCX, PPTX }

    private final Workspace workspace;
    private final Format format;

    public OfficeOpenXmlTool(Workspace workspace, Format format) {
        this.workspace = workspace;
        this.format = format;
    }

    @Override public String name() { return format.name().toLowerCase() + "_create"; }
    @Override public String description() {
        return "Create and validate a real " + format.name() + " Office Open XML file without Microsoft Office, COM, shell scripts, or downloads.";
    }
    @Override public String argumentSchema() {
        return switch (format) {
            case XLSX -> "{\"path\":\"relative .xlsx\",\"sheets\":[{\"name\":\"string\",\"rows\":[[\"cell\"]]}]}";
            case DOCX -> "{\"path\":\"relative .docx\",\"title\":\"string\",\"paragraphs\":[\"string\"]}";
            case PPTX -> "{\"path\":\"relative .pptx\",\"slides\":[{\"title\":\"string\",\"bullets\":[\"string\"]}]}";
        };
    }
    @Override public boolean mutating() { return true; }

    @Override public ToolResult validate(JsonNode arguments) {
        try {
            String path = ToolArguments.requiredText(arguments, "path");
            if (!path.toLowerCase().endsWith("." + format.name().toLowerCase())) {
                throw new IllegalArgumentException("path must end with ." + format.name().toLowerCase());
            }
            workspace.validateForWriteCreatingParents(path);
            JsonNode content = arguments.get(format == Format.XLSX ? "sheets" : format == Format.PPTX ? "slides" : "paragraphs");
            if (content == null || !content.isArray() || content.isEmpty()) {
                throw new IllegalArgumentException("structured content array must not be empty");
            }
            return ToolResult.success("validated");
        } catch (IllegalArgumentException | IOException e) {
            return ToolResult.failure(e.getMessage());
        }
    }

    @Override public ToolResult execute(JsonNode arguments) {
        ToolResult validation = validate(arguments);
        if (!validation.success()) return validation;
        Path temporary = null;
        try {
            Path target = workspace.resolveForWriteCreatingParents(arguments.path("path").asText());
            temporary = Files.createTempFile(target.getParent(), ".pironi-office-", ".tmp");
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temporary), StandardCharsets.UTF_8)) {
                switch (format) {
                    case DOCX -> writeDocx(zip, arguments);
                    case XLSX -> writeXlsx(zip, arguments);
                    case PPTX -> writePptx(zip, arguments);
                }
            }
            validatePackage(temporary);
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            return ToolResult.success("Created and validated " + workspace.root().relativize(target)
                    + " (" + Files.size(target) + " bytes)");
        } catch (IOException | RuntimeException e) {
            return ToolResult.failure(e.getMessage());
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
        }
    }

    private static void writeDocx(ZipOutputStream zip, JsonNode args) throws IOException {
        entry(zip, "[Content_Types].xml", types("/word/document.xml", "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"));
        entry(zip, "_rels/.rels", rootRels("word/document.xml"));
        StringBuilder body = new StringBuilder(paragraph(args.path("title").asText("Document"), true));
        args.path("paragraphs").forEach(p -> body.append(paragraph(p.asText(), false)));
        entry(zip, "word/document.xml", xml("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
                + body + "<w:sectPr/></w:body></w:document>"));
    }

    private static void writeXlsx(ZipOutputStream zip, JsonNode args) throws IOException {
        JsonNode sheets = args.path("sheets");
        StringBuilder overrides = new StringBuilder();
        StringBuilder sheetRefs = new StringBuilder();
        StringBuilder rels = new StringBuilder();
        int index = 1;
        for (JsonNode sheet : sheets) {
            overrides.append("<Override PartName=\"/xl/worksheets/sheet").append(index).append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
            sheetRefs.append("<sheet name=\"").append(escAttr(sheet.path("name").asText("Sheet" + index))).append("\" sheetId=\"").append(index).append("\" r:id=\"rId").append(index).append("\"/>");
            rels.append("<Relationship Id=\"rId").append(index).append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet").append(index).append(".xml\"/>");
            StringBuilder rows = new StringBuilder();
            int rowNumber = 1;
            for (JsonNode row : sheet.path("rows")) {
                rows.append("<row r=\"").append(rowNumber).append("\">");
                int column = 1;
                for (JsonNode cell : row) {
                    rows.append("<c r=\"").append(columnName(column++)).append(rowNumber)
                            .append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                            .append(esc(cell.asText())).append("</t></is></c>");
                }
                rows.append("</row>"); rowNumber++;
            }
            entry(zip, "xl/worksheets/sheet" + index + ".xml", xml("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>" + rows + "</sheetData></worksheet>"));
            index++;
        }
        entry(zip, "[Content_Types].xml", xml("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" + overrides + "</Types>"));
        entry(zip, "_rels/.rels", rootRels("xl/workbook.xml"));
        entry(zip, "xl/workbook.xml", xml("<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>" + sheetRefs + "</sheets></workbook>"));
        entry(zip, "xl/_rels/workbook.xml.rels", xml("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" + rels + "</Relationships>"));
    }

    private static void writePptx(ZipOutputStream zip, JsonNode args) throws IOException {
        JsonNode slides = args.path("slides");
        StringBuilder overrides = new StringBuilder("<Override PartName=\"/ppt/presentation.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml\"/><Override PartName=\"/ppt/slideMasters/slideMaster1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml\"/><Override PartName=\"/ppt/slideLayouts/slideLayout1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml\"/>");
        StringBuilder ids = new StringBuilder();
        StringBuilder rels = new StringBuilder();
        int index = 1;
        for (JsonNode slide : slides) {
            overrides.append("<Override PartName=\"/ppt/slides/slide").append(index).append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>");
            ids.append("<p:sldId id=\"").append(255 + index).append("\" r:id=\"rId").append(index).append("\"/>");
            rels.append("<Relationship Id=\"rId").append(index).append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide\" Target=\"slides/slide").append(index).append(".xml\"/>");
            StringBuilder text = new StringBuilder(shapeText(2, slide.path("title").asText("Slide " + index), 700000, 350000, 10800000, 900000, true));
            int y = 1500000;
            for (JsonNode bullet : slide.path("bullets")) {
                text.append(shapeText(10 + y, "• " + bullet.asText(), 900000, y, 10000000, 650000, false));
                y += 700000;
            }
            entry(zip, "ppt/slides/slide" + index + ".xml", xml("<p:sld xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\"><p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>" + text + "</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>"));
            entry(zip, "ppt/slides/_rels/slide" + index + ".xml.rels", xml("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/></Relationships>"));
            index++;
        }
        entry(zip, "[Content_Types].xml", xml("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>" + overrides + "</Types>"));
        entry(zip, "_rels/.rels", rootRels("ppt/presentation.xml"));
        entry(zip, "ppt/presentation.xml", xml("<p:presentation xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\"><p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rIdMaster\"/></p:sldMasterIdLst><p:sldIdLst>" + ids + "</p:sldIdLst><p:sldSz cx=\"12192000\" cy=\"6858000\"/><p:notesSz cx=\"6858000\" cy=\"9144000\"/></p:presentation>"));
        entry(zip, "ppt/_rels/presentation.xml.rels", xml("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" + rels + "<Relationship Id=\"rIdMaster\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster\" Target=\"slideMasters/slideMaster1.xml\"/></Relationships>"));
        String group = "<p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr></p:spTree>";
        entry(zip, "ppt/slideMasters/slideMaster1.xml", xml("<p:sldMaster xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\"><p:cSld>" + group + "</p:cSld><p:clrMap accent1=\"accent1\" accent2=\"accent2\" accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" bg1=\"lt1\" bg2=\"lt2\" folHlink=\"folHlink\" hlink=\"hlink\" tx1=\"dk1\" tx2=\"dk2\"/><p:sldLayoutIdLst><p:sldLayoutId id=\"1\" r:id=\"rId1\"/></p:sldLayoutIdLst><p:txStyles><p:titleStyle/><p:bodyStyle/><p:otherStyle/></p:txStyles></p:sldMaster>"));
        entry(zip, "ppt/slideMasters/_rels/slideMaster1.xml.rels", xml("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/></Relationships>"));
        entry(zip, "ppt/slideLayouts/slideLayout1.xml", xml("<p:sldLayout xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\" type=\"blank\"><p:cSld name=\"Blank\">" + group + "</p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>"));
        entry(zip, "ppt/slideLayouts/_rels/slideLayout1.xml.rels", xml("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster\" Target=\"../slideMasters/slideMaster1.xml\"/></Relationships>"));
    }

    private static String shapeText(int id, String value, int x, int y, int cx, int cy, boolean title) {
        return "<p:sp><p:nvSpPr><p:cNvPr id=\"" + id + "\" name=\"Text " + id + "\"/><p:cNvSpPr txBox=\"1\"/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x=\"" + x + "\" y=\"" + y + "\"/><a:ext cx=\"" + cx + "\" cy=\"" + cy + "\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:noFill/></p:spPr><p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:rPr lang=\"en-US\" sz=\"" + (title ? 2800 : 1800) + "\"/><a:t>" + esc(value) + "</a:t></a:r><a:endParaRPr lang=\"en-US\"/></a:p></p:txBody></p:sp>";
    }

    private static String paragraph(String value, boolean title) {
        return "<w:p><w:r>" + (title ? "<w:rPr><w:b/><w:sz w:val=\"32\"/></w:rPr>" : "")
                + "<w:t xml:space=\"preserve\">" + esc(value) + "</w:t></w:r></w:p>";
    }
    private static String rootRels(String target) { return xml("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"" + target + "\"/></Relationships>"); }
    private static String types(String part, String type) { return xml("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"" + part + "\" ContentType=\"" + type + "\"/></Types>"); }
    private static String xml(String body) { return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" + body; }
    private static String esc(String value) { return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
    private static String escAttr(String value) { return esc(value).replace("\"", "&quot;"); }
    private static String columnName(int column) { StringBuilder name = new StringBuilder(); while (column > 0) { column--; name.append((char) ('A' + column % 26)); column /= 26; } return name.reverse().toString(); }
    private static void entry(ZipOutputStream zip, String name, String content) throws IOException { zip.putNextEntry(new ZipEntry(name)); zip.write(content.getBytes(StandardCharsets.UTF_8)); zip.closeEntry(); }

    private void validatePackage(Path path) throws IOException {
        List<String> required = switch (format) {
            case DOCX -> List.of("[Content_Types].xml", "_rels/.rels", "word/document.xml");
            case XLSX -> List.of("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/_rels/workbook.xml.rels");
            case PPTX -> List.of("[Content_Types].xml", "_rels/.rels", "ppt/presentation.xml", "ppt/_rels/presentation.xml.rels");
        };
        try (ZipFile zip = new ZipFile(path.toFile(), StandardCharsets.UTF_8)) {
            for (String name : required) if (zip.getEntry(name) == null) throw new IOException("OOXML package missing " + name);
            var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            for (var entries = zip.entries(); entries.hasMoreElements();) {
                var item = entries.nextElement();
                if (item.getName().endsWith(".xml") || item.getName().endsWith(".rels")) {
                    try { factory.newDocumentBuilder().parse(zip.getInputStream(item)); }
                    catch (Exception e) { throw new IOException("Invalid XML part " + item.getName() + ": " + e.getMessage(), e); }
                }
            }
        }
    }
}
