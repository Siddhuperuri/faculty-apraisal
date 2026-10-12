package edu.svec.fams.report;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

/**
 * A CSV file Excel opens correctly: UTF-8 with a byte-order mark, CRLF line ends, every text cell quoted when it needs to
 * be, dates as ISO yyyy-mm-dd (the one form every locale reads as a date). A cell that starts with a character a
 * spreadsheet would take for a formula ({@code = + - @}, tab, carriage return) gets a leading apostrophe, so a name or a
 * remark typed by a user can never run as a formula when the file is opened.
 */
public final class CsvWriter {
    private CsvWriter() {}

    public static byte[] write(ExportTable table) {
        StringBuilder out = new StringBuilder("﻿");
        line(out, table.headers().stream().map(h -> (Object) h).toList());
        for (List<Object> row : table.rows()) line(out, row);
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void line(StringBuilder out, List<Object> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) out.append(',');
            out.append(cell(cells.get(i)));
        }
        out.append("\r\n");
    }

    static String cell(Object value) {
        if (value == null) return "";
        if (value instanceof LocalDate d) return d.toString();
        String s = value.toString();
        if (value instanceof String && !s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) s = "'" + s;
        boolean quote = s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0;
        return quote ? '"' + s.replace("\"", "\"\"") + '"' : s;
    }
}
