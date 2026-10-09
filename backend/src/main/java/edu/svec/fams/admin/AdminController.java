package edu.svec.fams.admin;

import edu.svec.fams.audit.AuditQueryService;
import edu.svec.fams.auth.FamsUserPrincipal;
import edu.svec.fams.common.ApiException;
import edu.svec.fams.console.ConsoleService;
import edu.svec.fams.ops.ImportHistoryService;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
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

    public AdminController(AdminUserService users, AccountImportService imports, ImportHistoryService importHistory,
                           AdminSetupService setup, AuditQueryService audit, ConsoleService console) {
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

    // ---- users ----

    @GetMapping("/users")
    public List<AdminUserService.UserRow> users(@RequestParam(required = false) String q,
                                                @RequestParam(required = false) String role) {
        return users.list(q, role);
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
