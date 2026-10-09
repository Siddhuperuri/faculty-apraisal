package edu.svec.fams.ops;

import edu.svec.fams.audit.AuditService;
import edu.svec.fams.auth.FamsUserPrincipal;
import edu.svec.fams.common.GeneratedKeys;
import edu.svec.fams.common.ValidationException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The administrator's record of trying a backup on a spare machine. A backup nobody has restored is a hope, so the date of
 * the last good test is shown beside the backups themselves.
 */
@Service
public class RestoreTestService {

    /** @param result PASSED or FAILED; @param recordedBy the administrator, by name or e-mail address */
    public record RestoreTest(long id, LocalDate testedOn, String result, String notes, String recordedBy, OffsetDateTime recordedAt) {}

    private static final int MAX_NOTES = 500;
    private static final int KEEP = 10;

    private final JdbcClient jdbc;
    private final AuditService audit;

    public RestoreTestService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** The latest tests, newest first. */
    @Transactional(readOnly = true)
    public List<RestoreTest> recent() {
        return jdbc.sql("""
                SELECT t.id, t.tested_on, t.result, t.notes, COALESCE(u.name, u.email) AS who, t.recorded_at
                FROM restore_tests t JOIN users u ON u.id = t.recorded_by
                ORDER BY t.tested_on DESC, t.id DESC""" + " LIMIT " + KEEP)
                .query((rs, n) -> new RestoreTest(rs.getLong("id"), rs.getObject("tested_on", LocalDate.class), rs.getString("result"),
                        rs.getString("notes"), rs.getString("who"), rs.getObject("recorded_at", OffsetDateTime.class)))
                .list();
    }

    /** The most recent test that passed, or null if none has. */
    @Transactional(readOnly = true)
    public RestoreTest lastPassed() {
        return recent().stream().filter(t -> "PASSED".equals(t.result())).findFirst().orElse(null);
    }

    @Transactional
    public RestoreTest record(FamsUserPrincipal actor, Map<String, Object> body) {
        Map<String, String> errors = new LinkedHashMap<>();
        LocalDate testedOn = null;
        try {
            testedOn = LocalDate.parse(String.valueOf(body.get("testedOn")));
            LocalDate today = LocalDate.now();
            if (testedOn.isAfter(today)) {
                errors.put("testedOn", "The date cannot be in the future.");
            } else if (testedOn.isBefore(today.minusYears(5))) {
                errors.put("testedOn", "The date is more than five years ago.");
            }
        } catch (DateTimeParseException e) {
            errors.put("testedOn", "Give the date the restore was tried.");
        }
        String result = body.get("result") instanceof String s ? s.strip().toUpperCase(Locale.ROOT) : "";
        if (!result.equals("PASSED") && !result.equals("FAILED")) errors.put("result", "Result must be PASSED or FAILED.");
        String notes = body.get("notes") instanceof String s && !s.isBlank() ? s.strip() : null;
        if (notes != null && notes.length() > MAX_NOTES) errors.put("notes", "Notes must be at most " + MAX_NOTES + " characters.");
        if (!errors.isEmpty()) throw new ValidationException(errors);

        long id = GeneratedKeys.insert(jdbc.sql("INSERT INTO restore_tests (tested_on, result, notes, recorded_by) VALUES (?,?,?,?)")
                .params(testedOn, result, notes, actor.id()));
        audit.recordDetails(actor.id(), "RESTORE_TEST_RECORDED", "RESTORE_TEST", id, Map.of("result", result, "testedOn", testedOn.toString()));
        return recent().stream().filter(t -> t.id() == id).findFirst().orElseThrow();
    }
}
