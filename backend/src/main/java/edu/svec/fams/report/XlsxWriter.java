package edu.svec.fams.report;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes a real Excel workbook (Office Open XML, {@code .xlsx}) with one worksheet and no library: a title line, an
 * optional subtitle, a styled header row, and the rows below. The header rows are frozen, the columns are sized to their
 * content (at most {@link #MAX_WIDTH} characters wide), long text wraps, dates are real Excel dates shown as
 * {@code dd-mmm-yyyy} and numbers are real numbers. Text is written as inline strings, so there is no shared-string
 * table to get out of step.
 */
public final class XlsxWriter {
    private XlsxWriter() {}

    static final int MIN_WIDTH = 10;
    static final int MAX_WIDTH = 60;
    /** The widest a column becomes because of its heading alone. */
    private static final int HEADER_WIDTH = 24;
    /** Excel will not hold more characters than this in one cell. */
    static final int MAX_CELL_CHARACTERS = 32_000;
    private static final double LINE_HEIGHT = 15.0;
    private static final double MAX_ROW_HEIGHT = 409.0;
    private static final LocalDate EXCEL_EPOCH = LocalDate.of(1899, 12, 30);

    // cellXfs indexes in STYLES
    private static final int S_TITLE = 1;
    private static final int S_SUBTITLE = 2;
    private static final int S_HEADER = 3;
    private static final int S_TEXT = 4;
    private static final int S_DATE = 5;
    private static final int S_INTEGER = 6;
    private static final int S_DECIMAL = 7;

    private static final String NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String XML = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n";

    private static final String CONTENT_TYPES = XML
            + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
            + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
            + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
            + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
            + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
            + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>"
            + "</Types>";

    private static final String ROOT_RELS = XML
            + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
            + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
            + "</Relationships>";

    private static final String WORKBOOK_RELS = XML
            + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
            + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
            + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>"
            + "</Relationships>";

    private static final String STYLES = XML
            + "<styleSheet xmlns=\"" + NS + "\">"
            + "<numFmts count=\"1\"><numFmt numFmtId=\"164\" formatCode=\"dd\\-mmm\\-yyyy\"/></numFmts>"
            + "<fonts count=\"4\">"
            + "<font><sz val=\"11\"/><name val=\"Calibri\"/><family val=\"2\"/></font>"
            + "<font><b/><sz val=\"14\"/><color rgb=\"FF1F3A5F\"/><name val=\"Calibri\"/><family val=\"2\"/></font>"
            + "<font><i/><sz val=\"10\"/><color rgb=\"FF5F6B7A\"/><name val=\"Calibri\"/><family val=\"2\"/></font>"
            + "<font><b/><sz val=\"11\"/><color rgb=\"FFFFFFFF\"/><name val=\"Calibri\"/><family val=\"2\"/></font>"
            + "</fonts>"
            + "<fills count=\"3\">"
            + "<fill><patternFill patternType=\"none\"/></fill>"
            + "<fill><patternFill patternType=\"gray125\"/></fill>"
            + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF1F3A5F\"/><bgColor indexed=\"64\"/></patternFill></fill>"
            + "</fills>"
            + "<borders count=\"2\">"
            + "<border><left/><right/><top/><bottom/><diagonal/></border>"
            + "<border><left style=\"thin\"><color rgb=\"FFBFC5CC\"/></left><right style=\"thin\"><color rgb=\"FFBFC5CC\"/></right>"
            + "<top style=\"thin\"><color rgb=\"FFBFC5CC\"/></top><bottom style=\"thin\"><color rgb=\"FFBFC5CC\"/></bottom><diagonal/></border>"
            + "</borders>"
            + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
            + "<cellXfs count=\"8\">"
            + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
            + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyAlignment=\"1\"><alignment vertical=\"center\"/></xf>"
            + "<xf numFmtId=\"0\" fontId=\"2\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>"
            + "<xf numFmtId=\"0\" fontId=\"3\" fillId=\"2\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\">"
            + "<alignment horizontal=\"center\" vertical=\"center\" wrapText=\"1\"/></xf>"
            + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyBorder=\"1\" applyAlignment=\"1\">"
            + "<alignment vertical=\"top\" wrapText=\"1\"/></xf>"
            + "<xf numFmtId=\"164\" fontId=\"0\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\" applyBorder=\"1\" applyAlignment=\"1\">"
            + "<alignment horizontal=\"left\" vertical=\"top\"/></xf>"
            + "<xf numFmtId=\"1\" fontId=\"0\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\" applyBorder=\"1\" applyAlignment=\"1\">"
            + "<alignment horizontal=\"left\" vertical=\"top\"/></xf>"
            + "<xf numFmtId=\"2\" fontId=\"0\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\" applyBorder=\"1\" applyAlignment=\"1\">"
            + "<alignment horizontal=\"left\" vertical=\"top\"/></xf>"
            + "</cellXfs>"
            + "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>"
            + "</styleSheet>";

    /** The workbook for {@code table}. */
    public static byte[] write(ExportTable table) {
        String sheetName = sheetName(table.sheetName());
        int columns = table.headers().size();
        boolean hasSubtitle = table.subtitle() != null && !table.subtitle().isBlank();
        int headerRow = hasSubtitle ? 3 : 2;
        int[] widths = widths(table);

        StringBuilder sheet = new StringBuilder(XML);
        sheet.append("<worksheet xmlns=\"").append(NS).append("\">");
        sheet.append("<sheetPr><pageSetUpPr fitToPage=\"1\"/></sheetPr>");
        int lastRow = headerRow + table.rows().size();
        sheet.append("<dimension ref=\"A1:").append(column(columns)).append(lastRow).append("\"/>");
        sheet.append("<sheetViews><sheetView workbookViewId=\"0\" tabSelected=\"1\">")
                .append("<pane ySplit=\"").append(headerRow).append("\" topLeftCell=\"A").append(headerRow + 1)
                .append("\" activePane=\"bottomLeft\" state=\"frozen\"/>")
                .append("<selection pane=\"bottomLeft\" activeCell=\"A").append(headerRow + 1).append("\" sqref=\"A").append(headerRow + 1).append("\"/>")
                .append("</sheetView></sheetViews>");
        sheet.append("<sheetFormatPr defaultRowHeight=\"15\"/>");
        sheet.append("<cols>");
        for (int c = 0; c < columns; c++) {
            sheet.append("<col min=\"").append(c + 1).append("\" max=\"").append(c + 1).append("\" width=\"").append(widths[c])
                    .append("\" customWidth=\"1\"/>");
        }
        sheet.append("</cols><sheetData>");

        sheet.append("<row r=\"1\" ht=\"26\" customHeight=\"1\">");
        text(sheet, "A1", S_TITLE, table.title());
        sheet.append("</row>");
        if (hasSubtitle) {
            sheet.append("<row r=\"2\">");
            text(sheet, "A2", S_SUBTITLE, table.subtitle());
            sheet.append("</row>");
        }

        sheet.append("<row r=\"").append(headerRow).append("\" ht=\"36\" customHeight=\"1\">");
        for (int c = 0; c < columns; c++) text(sheet, column(c + 1) + headerRow, S_HEADER, table.headers().get(c));
        sheet.append("</row>");

        int r = headerRow;
        for (List<Object> row : table.rows()) {
            r++;
            sheet.append("<row r=\"").append(r).append("\" ht=\"").append(height(row, widths)).append("\" customHeight=\"1\">");
            for (int c = 0; c < columns; c++) {
                Object v = c < row.size() ? row.get(c) : null;
                cell(sheet, column(c + 1) + r, v);
            }
            sheet.append("</row>");
        }
        sheet.append("</sheetData>");
        // The header row's filter buttons; no merged cells anywhere, so sorting and filtering always see plain rows.
        sheet.append("<autoFilter ref=\"A").append(headerRow).append(":").append(column(columns)).append(lastRow).append("\"/>");
        sheet.append("<pageMargins left=\"0.4\" right=\"0.4\" top=\"0.6\" bottom=\"0.6\" header=\"0.3\" footer=\"0.3\"/>");
        sheet.append("<pageSetup paperSize=\"9\" orientation=\"landscape\" fitToWidth=\"1\" fitToHeight=\"0\"/>");
        sheet.append("</worksheet>");

        String workbook = XML + "<workbook xmlns=\"" + NS + "\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                + "<sheets><sheet name=\"" + escape(sheetName) + "\" sheetId=\"1\" r:id=\"rId1\"/></sheets>"
                + "<definedNames>"
                + "<definedName name=\"_xlnm._FilterDatabase\" localSheetId=\"0\" hidden=\"1\">'" + escape(sheetName) + "'!$A$" + headerRow
                + ":$" + column(columns) + "$" + lastRow + "</definedName>"
                + "<definedName name=\"_xlnm.Print_Titles\" localSheetId=\"0\">'" + escape(sheetName) + "'!$" + headerRow
                + ":$" + headerRow + "</definedName></definedNames>"
                + "</workbook>";

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.setLevel(Deflater.DEFAULT_COMPRESSION);
            entry(zip, "[Content_Types].xml", CONTENT_TYPES);
            entry(zip, "_rels/.rels", ROOT_RELS);
            entry(zip, "xl/workbook.xml", workbook);
            entry(zip, "xl/_rels/workbook.xml.rels", WORKBOOK_RELS);
            entry(zip, "xl/styles.xml", STYLES);
            entry(zip, "xl/worksheets/sheet1.xml", sheet.toString());
        } catch (IOException e) {
            throw new IllegalStateException("The workbook could not be written", e); // a byte array does not fail
        }
        return out.toByteArray();
    }

    private static void entry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static void cell(StringBuilder sheet, String ref, Object v) {
        if (v == null) {
            sheet.append("<c r=\"").append(ref).append("\" s=\"").append(S_TEXT).append("\"/>");
        } else if (v instanceof LocalDate d) {
            sheet.append("<c r=\"").append(ref).append("\" s=\"").append(S_DATE).append("\"><v>")
                    .append(ChronoUnit.DAYS.between(EXCEL_EPOCH, d)).append("</v></c>");
        } else if (v instanceof Integer || v instanceof Long || v instanceof Short) {
            sheet.append("<c r=\"").append(ref).append("\" s=\"").append(S_INTEGER).append("\"><v>").append(v).append("</v></c>");
        } else if (v instanceof BigDecimal || v instanceof Double || v instanceof Float) {
            sheet.append("<c r=\"").append(ref).append("\" s=\"").append(S_DECIMAL).append("\"><v>")
                    .append(new BigDecimal(v.toString()).toPlainString()).append("</v></c>");
        } else {
            text(sheet, ref, S_TEXT, v.toString());
        }
    }

    private static void text(StringBuilder sheet, String ref, int style, String value) {
        sheet.append("<c r=\"").append(ref).append("\" s=\"").append(style).append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                .append(escape(limit(value))).append("</t></is></c>");
    }

    private static String limit(String s) {
        return s.length() <= MAX_CELL_CHARACTERS ? s : s.substring(0, MAX_CELL_CHARACTERS - 1) + "…";
    }

    /** Column widths in characters: the longest line of any cell in the column, within the limits. */
    private static int[] widths(ExportTable table) {
        int columns = table.headers().size();
        int[] w = new int[columns];
        // A long heading wraps over two lines in the header row instead of widening its column.
        for (int c = 0; c < columns; c++) w[c] = Math.min(HEADER_WIDTH, longestLine(table.headers().get(c)) + 4);
        for (List<Object> row : table.rows()) {
            for (int c = 0; c < columns && c < row.size(); c++) {
                w[c] = Math.max(w[c], longestLine(display(row.get(c))) + 2);
            }
        }
        for (int c = 0; c < columns; c++) w[c] = Math.min(MAX_WIDTH, Math.max(MIN_WIDTH, w[c]));
        return w;
    }

    /** The row's height in points: enough lines for the cell that wraps the most. */
    private static String height(List<Object> row, int[] widths) {
        int lines = 1;
        for (int c = 0; c < widths.length && c < row.size(); c++) {
            String text = display(row.get(c));
            if (text.isEmpty()) continue;
            int n = 0;
            for (String line : text.split("\n", -1)) n += Math.max(1, (int) Math.ceil(line.length() / (double) Math.max(1, widths[c] - 1)));
            lines = Math.max(lines, n);
        }
        return String.valueOf(Math.min(MAX_ROW_HEIGHT, lines * LINE_HEIGHT + 3));
    }

    private static String display(Object v) {
        if (v == null) return "";
        if (v instanceof LocalDate) return "00-Jan-0000";
        return v.toString();
    }

    private static int longestLine(String s) {
        int best = 0;
        for (String line : s.split("\n", -1)) best = Math.max(best, line.length());
        return best;
    }

    /** 1 -> A, 26 -> Z, 27 -> AA. */
    static String column(int n) {
        StringBuilder s = new StringBuilder();
        for (int v = n; v > 0; v = (v - 1) / 26) s.insert(0, (char) ('A' + (v - 1) % 26));
        return s.toString();
    }

    /** A legal worksheet name: at most 31 characters, none of {@code [ ] : * ? / \}, and not empty or quoted. */
    static String sheetName(String raw) {
        String s = raw == null ? "" : raw.replaceAll("[\\[\\]:*?/\\\\']", " ").strip();
        if (s.length() > 31) s = s.substring(0, 31).strip();
        return s.isEmpty() ? "Sheet1" : s;
    }

    /** XML text: markup characters escaped, characters XML 1.0 forbids replaced by a space. */
    static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length() + 16);
        s.codePoints().forEach(cp -> {
            switch (cp) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                default -> {
                    boolean legal = cp == '\t' || cp == '\n' || cp == '\r' || (cp >= 0x20 && cp <= 0xD7FF)
                            || (cp >= 0xE000 && cp <= 0xFFFD) || (cp >= 0x10000 && cp <= 0x10FFFF);
                    if (legal) {
                        out.appendCodePoint(cp);
                    } else {
                        out.append(' ');
                    }
                }
            }
        });
        return out.toString();
    }
}
