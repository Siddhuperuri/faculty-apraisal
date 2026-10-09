package edu.svec.fams.report;

import edu.svec.fams.auth.FamsUserPrincipal;
import java.nio.charset.StandardCharsets;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/appraisals/{id}")
public class ReportController {

    private final ReportService service;

    public ReportController(ReportService service) { this.service = service; }

    /**
     * The printed form as a PDF. A DRAFT until the appraisal is approved; after that, the stored official copy.
     * Always a download, never rendered inside a page.
     */
    @GetMapping("/report.pdf")
    public ResponseEntity<byte[]> report(@PathVariable long id, @AuthenticationPrincipal FamsUserPrincipal user) {
        ReportService.Report r = service.report(id, user);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(r.bytes().length)
                .cacheControl(CacheControl.noStore().cachePrivate())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(r.fileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Report-Kind", r.official() ? "official" : "draft")
                .body(r.bytes());
    }
}
