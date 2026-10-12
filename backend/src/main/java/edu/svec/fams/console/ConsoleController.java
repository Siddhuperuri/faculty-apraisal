package edu.svec.fams.console;

import edu.svec.fams.auth.FamsUserPrincipal;
import edu.svec.fams.report.AppraisalExportService;
import edu.svec.fams.report.Downloads;
import edu.svec.fams.report.ExportFile;
import edu.svec.fams.report.ExportRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The reviewers' consoles. Each is reachable by its own role only: the URL rule in SecurityConfig and the annotation
 * here both say so. (The administrator's overview is part of the admin area.)
 */
@RestController
@RequestMapping("/api")
public class ConsoleController {

    private final ConsoleService service;
    private final AppraisalExportService exports;

    public ConsoleController(ConsoleService service, AppraisalExportService exports) {
        this.service = service;
        this.exports = exports;
    }

    @GetMapping("/hod/console")
    @PreAuthorize("hasRole('HOD')")
    public ConsoleService.HodConsole hod(@AuthenticationPrincipal FamsUserPrincipal user,
                                         @RequestParam(required = false) Long academicYearId) {
        return service.hod(user, academicYearId);
    }

    /**
     * The appraisal details of the chosen faculty of the HoD's own departments, as a spreadsheet or a CSV file. Body
     * {@code {"facultyIds": [..], "academicYearId": n, "format": "xlsx"|"csv"}}. A faculty member of another department makes
     * the whole request "not found"; a draft is listed as not submitted with no content.
     */
    @PostMapping("/hod/appraisals/export")
    @PreAuthorize("hasRole('HOD')")
    public ResponseEntity<byte[]> exportAppraisals(@AuthenticationPrincipal FamsUserPrincipal user, @RequestBody Map<String, Object> body) {
        ExportFile file = exports.export(user, ExportRequest.parse(body, "facultyIds"));
        return Downloads.file(file.fileName(), file.format(), file.bytes());
    }

    @GetMapping("/principal/console")
    @PreAuthorize("hasRole('PRINCIPAL')")
    public ConsoleService.PrincipalConsole principal(@RequestParam(required = false) Long academicYearId) {
        return service.principal(academicYearId);
    }

    /** The Director Technical decides at the Principal's level, so the console shows the same college-wide picture. */
    @GetMapping("/director/console")
    @PreAuthorize("hasRole('DIRECTOR')")
    public ConsoleService.PrincipalConsole director(@RequestParam(required = false) Long academicYearId) {
        return service.principal(academicYearId);
    }
}
