package edu.svec.fams.admin;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads CSV as spreadsheet programs write it: quoted cells that may hold commas, quotation marks ("" inside quotes) and
 * line breaks, any of the usual line endings, a byte-order mark, and a semicolon or tab in place of the comma (Excel
 * uses a semicolon in some regions). Blank lines are skipped. It reads text only: it never evaluates a cell.
 */
final class CsvReader {
    private CsvReader() {}

    /** One non-blank line of the file: the cells and the line it starts on (the first line is 1). */
    record Row(int line, List<String> cells) {}

    /** The file cannot be read as CSV at all. The message says why in words an administrator can act on. */
    static final class CsvException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        CsvException(String message) { super(message); }
    }

    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

    /**
     * Text from the file's bytes: UTF-8 (what "CSV UTF-8" saves), and where the bytes are not valid UTF-8 Windows-1252
     * (what Excel's plain "CSV" saves), so accented letters survive either way. A leading byte-order mark is dropped.
     */
    static String decode(byte[] bytes) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            text = new String(bytes, WINDOWS_1252);
        }
        return text.startsWith("﻿") ? text.substring(1) : text;
    }

    static List<Row> parse(String text) {
        char delimiter = delimiter(text);
        List<Row> rows = new ArrayList<>();
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        int line = 1;
        int rowStart = 1;
        int n = text.length();
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < n && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    if (c == '\n') line++;
                    cell.append(c);
                }
            } else if (c == '"' && cell.length() == 0) {
                quoted = true;
            } else if (c == delimiter) {
                cells.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\r' || c == '\n') {
                if (c == '\r' && i + 1 < n && text.charAt(i + 1) == '\n') i++;
                cells.add(cell.toString());
                cell.setLength(0);
                add(rows, cells, rowStart);
                cells = new ArrayList<>();
                line++;
                rowStart = line;
            } else {
                cell.append(c);
            }
        }
        if (quoted) throw new CsvException("A quotation mark opened on line " + rowStart + " is never closed.");
        if (cell.length() > 0 || !cells.isEmpty()) {
            cells.add(cell.toString());
            add(rows, cells, rowStart);
        }
        return rows;
    }

    private static void add(List<Row> rows, List<String> cells, int line) {
        if (cells.stream().anyMatch(s -> !s.isBlank())) rows.add(new Row(line, cells));
    }

    /** The separator the first line uses most: a comma, a semicolon or a tab (outside quotation marks). */
    private static char delimiter(String text) {
        int comma = 0, semicolon = 0, tab = 0;
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') quoted = !quoted;
            else if (!quoted && (c == '\n' || c == '\r')) {
                if (comma + semicolon + tab > 0) break;   // the first line that has any
            } else if (!quoted) {
                switch (c) {
                    case ',' -> comma++;
                    case ';' -> semicolon++;
                    case '\t' -> tab++;
                    default -> { }
                }
            }
        }
        if (semicolon > comma && semicolon >= tab) return ';';
        if (tab > comma && tab > semicolon) return '\t';
        return ',';
    }
}
