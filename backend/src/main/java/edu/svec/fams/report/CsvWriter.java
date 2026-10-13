package edu.svec.fams.report;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * A genuine CSV file (RFC 4180) that Excel opens correctly: UTF-8 with a byte-order mark, CRLF between records, a field
 * quoted when it holds a comma, a quote or a line break and every quote inside it doubled, dates as {@code dd-MMM-yyyy}.
 * A line break inside a value is replaced by "; " so one record is always one line, whatever program reads the file. A cell that starts with a character a
 * spreadsheet would take for a formula ({@code = + - @}, tab, carriage return) gets a leading apostrophe, so a name or a
 * remark typed by a user can never run as a formula when the file is opened.
 */
public final class CsvWriter {
    private CsvWriter() {}

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH);

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
        if (value instanceof LocalDate d) return DATE.format(d);
        String s = oneLine(value.toString());
        if (value instanceof String && !s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) s = "'" + s;
        boolean quote = s.indexOf(',') >= 0 || s.indexOf('"') >= 0;   // no line break is left in s
        return quote ? '"' + s.replace("\"", "\"\"") + '"' : s;
    }

    /** Line breaks (CRLF, CR, LF, or several) become "; "; breaks at the very start or end are dropped. */
    static String oneLine(String s) {
        if (s.indexOf('\n') < 0 && s.indexOf('\r') < 0) return s;
        return s.strip().replaceAll("[ \\t]*[\\r\\n]+[ \\t]*", "; ");
    }
}
