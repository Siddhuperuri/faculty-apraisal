package edu.svec.fams.ops;

import edu.svec.fams.auth.FamsUserPrincipal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** What an administrator needs to run the system: backups, storage, account tidiness, imports, year readiness and handover. */
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class OpsController {

    private final BackupHealthService backups;
    private final StorageHealthService storage;
    private final RestoreTestService restoreTests;
    private final AccountHygieneService hygiene;
    private final ImportHistoryService imports;
    private final YearReadinessService readiness;
    private final HandoverService handover;

    public OpsController(BackupHealthService backups, StorageHealthService storage, RestoreTestService restoreTests,
                         AccountHygieneService hygiene, ImportHistoryService imports, YearReadinessService readiness,
                         HandoverService handover) {
        this.backups = backups;
        this.storage = storage;
        this.restoreTests = restoreTests;
        this.hygiene = hygiene;
        this.imports = imports;
        this.readiness = readiness;
        this.handover = handover;
    }

    @GetMapping("/backup-health")
    public BackupHealthService.BackupHealth backupHealth() { return backups.health(); }

    @GetMapping("/storage-health")
    public StorageHealthService.StorageHealth storageHealth() { return storage.health(); }

    @GetMapping("/restore-tests")
    public List<RestoreTestService.RestoreTest> restoreTests() { return restoreTests.recent(); }

    @PostMapping("/restore-tests")
    @ResponseStatus(HttpStatus.CREATED)
    public RestoreTestService.RestoreTest recordRestoreTest(@AuthenticationPrincipal FamsUserPrincipal me,
                                                            @RequestBody Map<String, Object> body) {
        return restoreTests.record(me, body);
    }

    @GetMapping("/account-hygiene")
    public AccountHygieneService.Hygiene accountHygiene() { return hygiene.hygiene(); }

    @GetMapping("/inactive-accounts")
    public List<AccountHygieneService.Inactive> inactiveAccounts(@RequestParam(defaultValue = "90") int days) {
        return hygiene.inactive(days);
    }

    @GetMapping("/imports")
    public List<ImportHistoryService.Entry> imports() { return imports.recent(); }

    @GetMapping("/imports/{id}/report")
    public ResponseEntity<byte[]> importReport(@PathVariable long id) {
        byte[] csv = imports.report(id).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"import-" + id + "-problems.csv\"")
                .header("X-Content-Type-Options", "nosniff")
                .body(csv);
    }

    @GetMapping("/academic-years/{id}/readiness")
    public YearReadinessService.Readiness yearReadiness(@PathVariable long id) { return readiness.readiness(id); }

    @GetMapping("/handover")
    public List<HandoverService.Note> handover() { return handover.notes(); }

    @PutMapping("/handover")
    public List<HandoverService.Note> saveHandover(@AuthenticationPrincipal FamsUserPrincipal me, @RequestBody Map<String, Object> body) {
        return handover.save(me, body);
    }
}
