package edu.svec.fams.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.svec.fams.common.ApiException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;

/** The two file writers on their own: a workbook any spreadsheet program opens, and a CSV file that cannot run a formula. */
class ExportWritersTest {

    private static Map<String, String> unzip(byte[] bytes) throws IOException {
        Map<String, String> parts = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) parts.put(e.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
        }
        return parts;
    }

    private static ExportTable table(List<List<Object>> rows) {
        return new ExportTable("Appraisals", "Faculty appraisal details - 2025-26 - CSE", "2 faculty.", List.of("S.No.", "Name", "Joined", "Notes"), rows);
    }

    @Test
    void everyPartOfTheWorkbookIsWellFormedXml() throws Exception {
        List<List<Object>> rows = new ArrayList<>();
        rows.add(new ArrayList<>(java.util.Arrays.asList(1, "A & B <C> \"D\" 'E'", LocalDate.of(2020, 6, 15), "x\u0001y\nline two")));
        rows.add(new ArrayList<>(java.util.Arrays.asList(2, null, null, "—")));
        Map<String, String> parts = unzip(XlsxWriter.write(table(rows)));
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        for (Map.Entry<String, String> e : parts.entrySet()) {
            f.newDocumentBuilder().parse(new ByteArrayInputStream(e.getValue().getBytes(StandardCharsets.UTF_8)));   // throws if not well-formed
        }
        String sheet = parts.get("xl/worksheets/sheet1.xml");
        assertTrue(sheet.contains("A &amp; B &lt;C&gt; &quot;D&quot; 'E'"));
        assertFalse(sheet.contains("\u0001"), "characters XML cannot hold are replaced");
        assertTrue(sheet.contains("x y\nline two"));
    }

    @Test
    void datesAreRealExcelDatesAndNumbersAreNumbers() throws Exception {
        List<List<Object>> rows = List.of(new ArrayList<>(java.util.Arrays.asList(7, "Name", LocalDate.of(2020, 6, 15), "")));
        String sheet = unzip(XlsxWriter.write(table(rows))).get("xl/worksheets/sheet1.xml");
        // 15 June 2020 is day 43997 of the Excel calendar
        assertTrue(sheet.contains("<c r=\"C4\" s=\"5\"><v>43997</v></c>"), sheet);
        assertTrue(sheet.contains("<c r=\"A4\" s=\"6\"><v>7</v></c>"), sheet);
    }

    @Test
    void theHeaderRowIsFrozenFilteredAndNothingIsMerged() throws Exception {
        String sheet = unzip(XlsxWriter.write(table(List.of()))).get("xl/worksheets/sheet1.xml");
        assertTrue(sheet.contains("<pane ySplit=\"3\" topLeftCell=\"A4\" activePane=\"bottomLeft\" state=\"frozen\"/>"));
        assertTrue(sheet.contains("<autoFilter ref=\"A3:D3\"/>"));
        assertFalse(sheet.contains("mergeCell"), "a merged title would break sorting and filtering");
    }

    @Test
    void withoutASubtitleTheHeaderIsRowTwoAndThereIsExactlyOneSheet() throws Exception {
        ExportTable t = new ExportTable("Appraisals", "Title", null, List.of("S.No.", "Name"),
                List.of(new ArrayList<>(java.util.Arrays.asList(1, "A")), new ArrayList<>(java.util.Arrays.asList(2, "B"))));
        Map<String, String> parts = unzip(XlsxWriter.write(t));
        String sheet = parts.get("xl/worksheets/sheet1.xml");
        assertEquals(1, parts.keySet().stream().filter(n -> n.startsWith("xl/worksheets/")).count());
        assertTrue(sheet.contains("<dimension ref=\"A1:B4\"/>"), sheet);
        assertTrue(sheet.contains("<c r=\"A1\" s=\"1\" t=\"inlineStr\">"));
        assertTrue(sheet.contains("<c r=\"A2\" s=\"3\" t=\"inlineStr\"><is><t xml:space=\"preserve\">S.No.</t>"));
        assertTrue(sheet.contains("<autoFilter ref=\"A2:B4\"/>"));
        assertTrue(sheet.contains("<c r=\"A4\" s=\"6\"><v>2</v></c>"));
        assertFalse(sheet.contains("r=\"5\""), "no phantom rows");
    }

    @Test
    void columnsFitTheirContentWithinTheLimits() throws Exception {
        String longText = "x".repeat(400);
        List<List<Object>> rows = List.of(new ArrayList<>(java.util.Arrays.asList(1, "Al", null, longText)));
        String sheet = unzip(XlsxWriter.write(table(rows))).get("xl/worksheets/sheet1.xml");
        assertTrue(sheet.contains("<col min=\"1\" max=\"1\" width=\"" + XlsxWriter.MIN_WIDTH + "\""), "narrow columns get the minimum");
        assertTrue(sheet.contains("<col min=\"4\" max=\"4\" width=\"" + XlsxWriter.MAX_WIDTH + "\""), "long text is capped, and wraps");
    }

    @Test
    void aSheetNameIsMadeLegal() {
        assertEquals("Sheet1", XlsxWriter.sheetName(""));
        assertEquals("a b c", XlsxWriter.sheetName("a/b:c"));
        assertEquals(31, XlsxWriter.sheetName("x".repeat(60)).length());
        assertEquals("AA", XlsxWriter.column(27));
    }

    @Test
    void csvIsUtf8WithABomQuotedWhereNeededAndSafeFromFormulas() {
        List<List<Object>> rows = List.of(
                new ArrayList<>(java.util.Arrays.asList(1, "=SUM(A1)", LocalDate.of(2020, 6, 15), "say \"hi\", then\nleave")),
                new ArrayList<>(java.util.Arrays.asList(2, "-5 apples", null, "+1")));
        byte[] bytes = CsvWriter.write(table(rows));
        assertEquals((byte) 0xEF, bytes[0]);
        String text = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(text.startsWith("﻿S.No.,Name,Joined,Notes\r\n"));
        assertTrue(text.contains("1,'=SUM(A1),15-Jun-2020,\"say \"\"hi\"\", then; leave\"\r\n"), text);
        assertTrue(text.contains("2,'-5 apples,,'+1\r\n"), text);
    }

    @Test
    void lineBreaksOfEveryKindBecomeOneReadableSeparator() {
        assertEquals("a; b", CsvWriter.cell("a\nb"));
        assertEquals("a; b", CsvWriter.cell("a\r\nb"));
        assertEquals("a; b", CsvWriter.cell("a\rb"));
        assertEquals("a; b; c", CsvWriter.cell("a \n\n b\n\nc"));
        assertEquals("a", CsvWriter.cell("\n a \r\n"));
        assertEquals("\"x, \"\"y\"\"; z\"", CsvWriter.cell("x, \"y\"\nz"));
        assertEquals("", CsvWriter.cell(null));
        assertEquals("—", CsvWriter.cell("—"));
    }

    @Test
    void aCsvRecordNeverSpansTwoLines() {
        List<List<Object>> rows = List.of(new ArrayList<>(java.util.Arrays.asList(1, "one\ntwo", null, "x\r\ny")));
        String text = new String(CsvWriter.write(table(rows)), StandardCharsets.UTF_8);
        assertEquals(2, text.split("\r\n").length, "a header line and one record line");
        assertFalse(text.replace("\r\n", "").contains("\n"));
    }

    @Test
    void theFormatIsOneOfTwo() {
        assertEquals(ExportFormat.XLSX, ExportFormat.parse(" XLSX "));
        assertEquals(ExportFormat.CSV, ExportFormat.parse("csv"));
        assertThrows(ApiException.class, () -> ExportFormat.parse("pdf"));
        assertThrows(ApiException.class, () -> ExportFormat.parse(null));
    }
}
