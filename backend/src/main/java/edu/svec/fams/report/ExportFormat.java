package edu.svec.fams.report;

import edu.svec.fams.common.ApiException;
import java.util.Locale;

/** The file formats a table can be downloaded in. */
public enum ExportFormat {
    XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    CSV("csv", "text/csv; charset=utf-8");

    private final String extension;
    private final String contentType;

    ExportFormat(String extension, String contentType) {
        this.extension = extension;
        this.contentType = contentType;
    }

    public String extension() { return extension; }

    public String contentType() { return contentType; }

    /** "xlsx" or "csv" (any case); anything else, or nothing, is a request error. */
    public static ExportFormat parse(Object raw) {
        if (raw instanceof String s) {
            String v = s.strip().toLowerCase(Locale.ROOT);
            for (ExportFormat f : values()) {
                if (f.extension.equals(v)) return f;
            }
        }
        throw ApiException.badRequest("Choose the format: xlsx or csv.");
    }

    /** The file's bytes. */
    public byte[] render(ExportTable table) {
        return this == XLSX ? XlsxWriter.write(table) : CsvWriter.write(table);
    }
}
