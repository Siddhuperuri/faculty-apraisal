package edu.svec.fams.report;

import java.nio.charset.StandardCharsets;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** The response for a generated file: an attachment, never cached (it holds people's details), never sniffed. */
public final class Downloads {
    private Downloads() {}

    public static ResponseEntity<byte[]> file(String fileName, ExportFormat format, byte[] bytes) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(format.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(fileName, StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore())
                .contentLength(bytes.length)
                .body(bytes);
    }
}
