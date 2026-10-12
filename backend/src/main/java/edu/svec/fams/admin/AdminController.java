package edu.svec.fams.admin;

import edu.svec.fams.audit.AuditQueryService;
import edu.svec.fams.auth.FamsUserPrincipal;
import edu.svec.fams.common.ApiException;
import edu.svec.fams.console.ConsoleService;
import edu.svec.fams.ops.ImportHistoryService;
import edu.svec.fams.report.AppraisalExportService;
import edu.svec.fams.report.Downloads;
import edu.svec.fams.report.ExportFile;
import edu.svec.fams.report.ExportRequest;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administration. Reachable only by the ADMIN role: the URL rule in SecurityConfig and the annotation below both say so, so
 * removing one by accident does not open the area. Bodies are read as maps and validated field by field (clear messages,
 * and unknown fields are ignored rather than trusted).
 */
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final AdminUserService users;
    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final AccountImportService imports;
    private final ImportHistoryService importHistory;
    private final AdminSetupService setup;
    private final AuditQueryService audit;
    private final ConsoleService console;
    private final AdminAnalyticsService analytics;
    private final AdminBulkService bulk;
    private final AppraisalExportService exports;

    public AdminController(AdminUserService users, AccountImportService imports, ImportHistoryService importHistory,
                           AdminSetupService setup, AuditQueryService audit, ConsoleService console,
                           AdminAnalyticsService analytics, AdminBulkService bulk, AppraisalExportService exports) {
        this.analytics = analytics;
        this.bulk = bulk;
        this.exports = exports;
        this.users = users;
        this.imports = imports;
        this.importHistory = importHistory;
        this.setup = setup;
        this.audit = audit;
        this.console = console;
    }

    /** The administrator's console: account counts, set-up checks, appraisal counts by stage (never names) and recent activity. */
    @GetMapping("/overview")
    public ConsoleService.AdminOverview overview() { return console.admin(); }

    @GetMapping("/reference")
    public AdminSetupService.Reference reference() { return setup.reference(); }

    /** The marks each institutional role carries in the latest scoring policy of a year. */
    @GetMapping("/role-marks")
    public List<AdminSetupService.RoleMarks> roleMarks(@RequestParam long academicYearId) { return setup.roleMarks(academicYearId); }

    @PutMapping("/role-marks")
    public List<AdminSetupService.RoleMarks> setRoleMarks(@AuthenticationPrincipal FamsUserPrincipal me,
                                                          @RequestBody Map<String, Object> body) {
        return setup.setRoleMarks(me, body);
    }

    // ---- users ----

    @GetMapping("/users")
    public List<AdminUserService.UserRow> users(@RequestParam(required = false) String q,
                                                @RequestParam(required = false) String role,
                                                @RequestParam(required = false) String status,
                                                @RequestParam(required = false) Long departmentId) {
        return users.list(q, role, status, departmentId);
    }

    /** Body {@code {"role": "ADMIN"}} (and {@code hodDepartmentIds} for a Head of the Department): a different role for the account. */
    @PutMapping("/users/{id}/role")
    public AdminUserService.UserDetail changeRole(@AuthenticationPrincipal FamsUserPrincipal me, @PathVariable long id,
                                                  @RequestBody Map<String, Object> body) {
        return users.changeRole(me, id, body);
    }

    /** Appraisal progress and user counts, aggregated, for the dashboard (no names and no appraisal content). */
    @GetMapping("/analytics")
    public AdminAnalyticsService.Analytics analytics(@RequestParam(required = false) Long academicYearId) {
        return analytics.analytics(academicYearId);
    }

    @GetMapping("/users/{id}")
    public AdminUserService.UserDetail user(@PathVariable long id) { return users.get(id); }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminUserService.IssuedPassword createUser(@AuthenticationPrincipal FamsUserPrincipal me,
                                                      @RequestBody Map<String, Object> body) {
        return users.create(me, body);
    }

    /**
     * Creates the accounts listed in a CSV file (the request body is the file itself). All or nothing: when any row is
     * wrong, nothing is created and the response lists the rows to correct.
     */
    @PostMapping("/users/import")
    public AccountImportService.Result importUsers(@AuthenticationPrincipal FamsUserPrincipal me,
                                                   @RequestBody(required = false) byte[] file) {
        AccountImportService.Result result;
        try {
            result = imports.importAccounts(me, file == null ? new byte[0] : file);
        } catch (ApiException e) {
            noteImport(() -> importHistory.recordUnreadable(me, e.getMessage()));
            throw e;
        }
        noteImport(() -> importHistory.record(me, result));
        return result;
    }

    /** The history is a convenience: a failure to write it must not undo, or hide, an import that has happened. */
    private static void noteImport(Runnable note) {
        try {
            note.run();
        } catch (RuntimeException e) {
            log.warn("Could not record the import in the history", e);
        }
    }

    @PutMapping("/users/{id}")
    public AdminUserService.UserDetail updateUser(@AuthenticationPrincipal FamsUserPrincipal me, @PathVariable long id,
                                                  @RequestBody Map<String, Object> body) {
        return users.update(me, id, body);
    }

    @PostMapping("/users/{id}/reset-password")
    public AdminUserService.IssuedPassword resetPassword(@AuthenticationPrincipal FamsUserPrincipal me, @PathVariable long id) {
        return users.resetPassword(me, id);
    }

    /**
     * Body {@code {"ids": [..]}}: sends each active user a one-time, expiring link to choose a new password (never a
     * password). The answer counts what was sent, skipped and failed, with the reason for each user not sent.
     */
    @PostMapping("/users/send-reset-links")
    public AdminBulkService.LinkResult sendResetLinks(@AuthenticationPrincipal FamsUserPrincipal me, @RequestBody Map<String, Object> body) {
        return bulk.sendResetLinks(me, ExportRequest.ids(body, "ids", AdminBulkService.MAX_LINKS_PER_REQUEST));
    }

    /** Body {@code {"ids": [..], "format": "xlsx"|"csv"}}: the chosen users as a download. No password or security field is in it. */
    @PostMapping("/users/export")
    public ResponseEntity<byte[]> exportUsers(@AuthenticationPrincipal FamsUserPrincipal me, @RequestBody Map<String, Object> body) {
        ExportFile r = bulk.exportUsers(me, ExportRequest.parse(body, "ids"));
        return Downloads.file(r.fileName(), r.format(), r.bytes());
    }

    // ---- appraisal details of faculty (export) ----

    /** The faculty an administrator may choose from for the export: by year, department, cadre and appraisal status. */
    @GetMapping("/appraisals/roster")
    public ConsoleService.ExportRoster exportRoster(@RequestParam(required = false) Long academicYearId,
                                                    @RequestParam(required = false) Long departmentId,
                                                    @RequestParam(required = false) Long cadreId,
                                                    @RequestParam(required = false) String status) {
        return console.exportRoster(academicYearId, departmentId, cadreId, status);
    }

    /** Body {@code {"facultyIds": [..], "academicYearId": n, "format": "xlsx"|"csv"}}: the appraisal details as a download. */
    @PostMapping("/appraisals/export")
    public ResponseEntity<byte[]> exportAppraisals(@AuthenticationPrincipal FamsUserPrincipal me, @RequestBody Map<String, Object> body) {
        ExportFile r = exports.export(me, ExportRequest.parse(body, "facultyIds"));
        return Downloads.file(r.fileName(), r.format(), r.bytes());
    }

    // ---- departments, academic years, scoring policy ----

    @PostMapping("/departments")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminSetupService.Department createDepartment(@AuthenticationPrincipal FamsUserPrincipal me,
                                                         @RequestBody Map<String, Object> body) {
        return setup.createDepartment(me, body);
    }

    @PutMapping("/departments/{id}")
    public AdminSetupService.Department updateDepartment(@AuthenticationPrincipal FamsUserPrincipal me, @PathVariable long id,
                                                         @RequestBody Map<String, Object> body) {
        return setup.updateDepartment(me, id, body);
    }

    @PostMapping("/academic-years")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminSetupService.Year createYear(@AuthenticationPrincipal FamsUserPrincipal me,
                                             @RequestBody Map<String, Object> body) {
        return setup.createYear(me, body);
    }

    @PutMapping("/academic-years/{id}")
    public AdminSetupService.Year setYearActive(@AuthenticationPrincipal FamsUserPrincipal me, @PathVariable long id,
                                                @RequestBody Map<String, Object> body) {
        return setup.setYearActive(me, id, body);
    }

    @GetMapping("/policies")
    public List<AdminSetupService.PolicyVersion> policies(@RequestParam long academicYearId) {
        return setup.policies(academicYearId);
    }

    @PostMapping("/policies")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminSetupService.PolicyVersion publishPolicy(@AuthenticationPrincipal FamsUserPrincipal me,
                                                         @RequestBody Map<String, Object> body) {
        return setup.publishPolicy(me, body);
    }

    // ---- audit trail ----

    @GetMapping("/audit")
    public AuditQueryService.Page audit(@RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "25") int size,
                                        @RequestParam(required = false) String action,
                                        @RequestParam(required = false) String entityType,
                                        @RequestParam(required = false) Long entityId) {
        return audit.query(page, size, action, entityType, entityId);
    }

    /** Deletes every audit entry matching the filters, or all of them when none is given. Records that it did. */
    @DeleteMapping("/audit")
    public Map<String, Integer> deleteAudit(@AuthenticationPrincipal FamsUserPrincipal me,
                                            @RequestParam(required = false) String action,
                                            @RequestParam(required = false) String entityType,
                                            @RequestParam(required = false) Long entityId) {
        return Map.of("removed", audit.delete(me.id(), null, action, entityType, entityId));
    }

    @DeleteMapping("/audit/{id}")
    public Map<String, Integer> deleteAuditEntry(@AuthenticationPrincipal FamsUserPrincipal me, @PathVariable long id) {
        return Map.of("removed", audit.delete(me.id(), id, null, null, null));
    }
}
