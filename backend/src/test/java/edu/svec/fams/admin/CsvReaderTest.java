package edu.svec.fams.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class CsvReaderTest {

    private static List<String> cells(CsvReader.Row row) { return row.cells(); }

    @Test
    void readsPlainRowsWithAnyLineEndingAndSkipsBlankLines() {
        List<CsvReader.Row> rows = CsvReader.parse("a,b,c\r\n1,2,3\n\n4,5,6\r7,8,9");
        assertEquals(4, rows.size());
        assertEquals(List.of("a", "b", "c"), cells(rows.get(0)));
        assertEquals(List.of("7", "8", "9"), cells(rows.get(3)));
        assertEquals(1, rows.get(0).line());
        assertEquals(2, rows.get(1).line());
        assertEquals(4, rows.get(2).line());      // the blank line still counts
    }

    @Test
    void readsQuotedCellsWithCommasQuotationMarksAndLineBreaks() {
        List<CsvReader.Row> rows = CsvReader.parse("name,note\n\"Rao, K. V.\",\"said \"\"hi\"\"\"\n\"two\nlines\",x\nlast,row\n");
        assertEquals(List.of("Rao, K. V.", "said \"hi\""), cells(rows.get(1)));
        assertEquals(List.of("two\nlines", "x"), cells(rows.get(2)));
        assertEquals(3, rows.get(2).line());
        assertEquals(5, rows.get(3).line());      // the line break inside the quotes is counted
    }

    @Test
    void keepsEmptyCellsAndAnEmptyQuotedCell() {
        assertEquals(List.of("a", "", "c", ""), cells(CsvReader.parse("a,,c,\"\"").get(0)));
    }

    @Test
    void usesTheSemicolonOrTabTheFirstLineUses() {
        assertEquals(List.of("Name", "E-mail"), cells(CsvReader.parse("Name;E-mail\nA, B;a@b.co").get(0)));
        assertEquals(List.of("A, B", "a@b.co"), cells(CsvReader.parse("Name;E-mail\nA, B;a@b.co").get(1)));
        assertEquals(List.of("A", "a@b.co"), cells(CsvReader.parse("Name\tE-mail\nA\ta@b.co").get(1)));
        // a comma inside quotes on the first line does not make the file comma-separated
        assertEquals(List.of("A, B", "C"), cells(CsvReader.parse("\"A, B\";C\n1;2").get(0)));
    }

    @Test
    void anUnclosedQuotationMarkIsAnErrorThatSaysWhere() {
        CsvReader.CsvException e = assertThrows(CsvReader.CsvException.class, () -> CsvReader.parse("a,b\n1,\"oops\n3,4\n"));
        assertEquals("A quotation mark opened on line 2 is never closed.", e.getMessage());
    }

    @Test
    void decodesUtf8WithOrWithoutAByteOrderMarkAndFallsBackToWindows1252() {
        assertEquals("Name,Café", CsvReader.decode("﻿Name,Café".getBytes(StandardCharsets.UTF_8)));
        assertEquals("Name,Café", CsvReader.decode("Name,Café".getBytes(StandardCharsets.UTF_8)));
        // Excel's plain "CSV" writes Windows-1252: the lone 0xE9 is not valid UTF-8
        assertEquals("Name,Café", CsvReader.decode("Name,Café".getBytes(Charset.forName("windows-1252"))));
    }
}
