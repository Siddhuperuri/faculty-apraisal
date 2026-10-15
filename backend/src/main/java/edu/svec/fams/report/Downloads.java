package edu.svec.fams.report;

import java.nio.charset.StandardCharsets;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

/** The response for a generated file: an attachment, never cached (it holds people's details), never sniffed. */
public final class Downloads {
    private Downloads() {}

    /** A zip of printed forms: how many were put in it and how many selected ones were left out go in two headers. */
    public static ResponseEntity<byte[]> zip(String fileName, byte[] bytes, int included, int skipped) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "application/zip")
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(fileName, StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header("X-Pdf-Included", String.valueOf(included))
                .header("X-Pdf-Skipped", String.valueOf(skipped))
                .cacheControl(CacheControl.noStore())
                .contentLength(bytes.length)
                .body(bytes);
    }

    public static ResponseEntity<byte[]> file(String fileName, ExportFormat format, byte[] bytes) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, format.contentType())   // exactly "text/csv; charset=utf-8" for a CSV file
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(fileName, StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore())
                .contentLength(bytes.length)
                .body(bytes);
    }
}
