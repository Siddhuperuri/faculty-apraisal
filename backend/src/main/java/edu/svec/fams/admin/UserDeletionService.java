package edu.svec.fams.admin;

import edu.svec.fams.audit.AuditService;
import edu.svec.fams.auth.FamsUserPrincipal;
import edu.svec.fams.common.ApiException;
import edu.svec.fams.common.ValidationException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Removes an account for good (the administrator's "Delete User Permanently").
 *
 * <p>What goes: the sign-in, the faculty record, the head-of-department assignments, any password-reset links and every
 * appraisal that was never sent for review, with everything entered in it. What cannot go is what the append-only
 * records need: the review history, the Head of the Department's messages, an issued official report, and the
 * operations log (imports, restore tests, hand-over notes) point at the account. When any of those exists the account
 * row stays as an anonymous stub: no e-mail address, name, employee ID, contact number or password, marked deleted, which
 * no list, count, export or sign-in sees; the appraisals that are kept lose the person's contact details and their
 * faculty record is made anonymous. Otherwise the account row is deleted outright.
 *
 * <p>Nothing else is touched: other users, departments and appraisals are never affected. Refused for the
 * administrator's own account and for the only active administrator. The administrator must type the account's e-mail
 * address, which is checked here, not only in the browser. The audit trail records who removed which account (by number,
 * with its role and counts), never its e-mail address or name.
 */
@Service
public class UserDeletionService {

    /** What deleting an account would do, shown to the administrator before they confirm. */
    public record Preview(long id, String email, String name, String role, String status,
                          boolean canDelete, String blockedReason,
                          int appraisalsRemoved, int appraisalsKept, boolean keepsAnonymousRecord,
                          List<String> departmentsUnassigned, List<String> departmentsLeftWithoutHead) {}

    public record Result(long id, String outcome, int appraisalsRemoved, int appraisalsKept, int departmentsUnassigned) {}

    /** The columns, in every table, that point at an account; the removal below accounts for each one. */
    public static final List<String> REFERENCES = List.of(
            "faculty_profiles.user_id", "hod_assignments.user_id", "password_reset_tokens.user_id",
            "review_actions.actor_id", "appraisal_messages.sender_id", "import_history.admin_id",
            "restore_tests.recorded_by", "handover_notes.updated_by", "dean_assignments.user_id");

    private static final String KEPT_REFERENCES = """
            SELECT (SELECT COUNT(*) FROM review_actions WHERE actor_id = :id)
                 + (SELECT COUNT(*) FROM appraisal_messages WHERE sender_id = :id)
                 + (SELECT COUNT(*) FROM import_history WHERE admin_id = :id)
                 + (SELECT COUNT(*) FROM restore_tests WHERE recorded_by = :id)
                 + (SELECT COUNT(*) FROM handover_notes WHERE updated_by = :id)
                 + (SELECT COUNT(*) FROM dean_assignments WHERE user_id = :id)""";

    private record Target(long id, String email, String role, String status, String name) {}

    private record Appraisal(long id, boolean locked) {}

    private final JdbcClient jdbc;
    private final AuditService audit;

    public UserDeletionService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    // ---- what would happen ----

    @Transactional(readOnly = true)
    public Preview preview(FamsUserPrincipal actor, long id) {
        Target t = load(id, false);
        List<Appraisal> appraisals = appraisals(id);
        int kept = (int) appraisals.stream().filter(Appraisal::locked).count();
        int removed = appraisals.size() - kept;
        List<String> unassigned = new ArrayList<>();
        List<String> orphaned = new ArrayList<>();
        jdbc.sql("""
                SELECT d.code, d.name,
                       (SELECT COUNT(*) FROM hod_assignments h2 JOIN users u2 ON u2.id = h2.user_id
                         WHERE h2.department_id = d.id AND h2.user_id <> :id AND u2.status = 'ACTIVE' AND u2.deleted_at IS NULL) AS others
                FROM hod_assignments h JOIN departments d ON d.id = h.department_id
                WHERE h.user_id = :id ORDER BY d.code""")
                .param("id", id)
                .query(rs -> {
                    String label = rs.getString(1) + " (" + rs.getString(2) + ")";
                    unassigned.add(label);
                    if (rs.getInt(3) == 0) orphaned.add(label);
                });
        String blocked = blockedReason(actor, t, false);
        boolean stub = kept > 0 || retainedReferences(id) > 0;
        return new Preview(t.id(), t.email(), t.name(), t.role(), t.status(), blocked == null, blocked,
                removed, kept, stub, unassigned, orphaned);
    }

    // ---- doing it ----

    @Transactional
    public Result delete(FamsUserPrincipal actor, long id, Map<String, Object> body) {
        Target t = load(id, true);
        String blocked = blockedReason(actor, t, true);
        if (blocked != null) throw ApiException.conflict(blocked);

        Object typed = body == null ? null : body.get("confirmEmail");
        if (!(typed instanceof String s) || !s.strip().toLowerCase(Locale.ROOT).equals(t.email().toLowerCase(Locale.ROOT))) {
            throw new ValidationException(Map.of("confirmEmail",
                    "Type the e-mail address of the account exactly as shown to confirm that it is to be deleted."));
        }

        // appraisals: those never sent for review go with everything in them; the rest stay, anonymous
        List<Appraisal> appraisals = appraisals(id);
        int removed = 0;
        int kept = 0;
        for (Appraisal a : appraisals) {
            if (a.locked()) {
                kept++;
                continue;
            }
            jdbc.sql("DELETE FROM appraisal_scores WHERE appraisal_id = ?").param(a.id()).update();
            jdbc.sql("DELETE FROM appraisals WHERE id = ?").param(a.id()).update();   // every section table cascades
            removed++;
        }
        if (kept > 0) {
            jdbc.sql("""
                    UPDATE general_information SET contact_no = NULL, research_ids = NULL
                    WHERE appraisal_id IN (SELECT a.id FROM appraisals a JOIN faculty_profiles fp ON fp.id = a.faculty_id WHERE fp.user_id = ?)""")
                    .param(id).update();
            jdbc.sql("""
                    UPDATE faculty_profiles SET name = 'Deleted user', employee_id = CONCAT('DELETED-', user_id), contact_no = NULL,
                           qualification = NULL, specialization = NULL, phd_status = 'NOT_APPLICABLE',
                           joining_date_institution = NULL, joining_date_designation = NULL,
                           teaching_experience_years = NULL, industry_experience_years = NULL, research_experience_years = NULL,
                           orcid = NULL, scopus_id = NULL, google_scholar_id = NULL, vidwan_id = NULL
                    WHERE user_id = ?""").param(id).update();
        } else {
            jdbc.sql("DELETE FROM faculty_profiles WHERE user_id = ?").param(id).update();
        }

        int unassigned = jdbc.sql("DELETE FROM hod_assignments WHERE user_id = ?").param(id).update();
        jdbc.sql("DELETE FROM password_reset_tokens WHERE user_id = ?").param(id).update();

        boolean stub = kept > 0 || retainedReferences(id) > 0;
        if (stub) {
            jdbc.sql("""
                    UPDATE users SET email = CONCAT('deleted-user-', id, '@deleted.invalid'), password_hash = NULL,
                           name = 'Deleted user', employee_id = NULL, contact_no = NULL, status = 'DISABLED',
                           must_change_password = FALSE, session_version = session_version + 1, deleted_at = CURRENT_TIMESTAMP
                    WHERE id = ?""").param(id).update();
        } else {
            jdbc.sql("DELETE FROM users WHERE id = ?").param(id).update();
        }

        audit.recordDetails(actor.id(), "USER_DELETED", "USER", id,
                Map.of("role", t.role(), "anonymousRecordKept", stub, "appraisalsRemoved", removed,
                        "appraisalsKept", kept, "departmentsUnassigned", unassigned));
        return new Result(id, stub ? "ANONYMIZED" : "REMOVED", removed, kept, unassigned);
    }

    // ---- helpers ----

    /** The account (not already deleted), locked for the removal; 404 when there is none. */
    private Target load(long id, boolean lock) {
        return jdbc.sql("""
                SELECT u.id, u.email, u.role, u.status, COALESCE(fp.name, u.name) AS name
                FROM users u LEFT JOIN faculty_profiles fp ON fp.user_id = u.id
                WHERE u.id = ? AND u.deleted_at IS NULL""" + (lock ? " FOR UPDATE OF u" : ""))
                .param(id)
                .query((rs, n) -> new Target(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)))
                .optional().orElseThrow(ApiException::notFound);
    }

    /** Why the account may not be deleted, or null when it may. */
    private String blockedReason(FamsUserPrincipal actor, Target t, boolean lock) {
        if (t.id() == actor.id()) return "You cannot delete the account you are signed in with.";
        if ("ADMIN".equals(t.role()) && "ACTIVE".equals(t.status())) {
            // Locking the other active administrators makes two administrators removing each other wait for one another.
            int others = jdbc.sql("""
                    SELECT id FROM users WHERE role = 'ADMIN' AND status = 'ACTIVE' AND deleted_at IS NULL AND id <> ?""" + (lock ? " FOR UPDATE" : ""))
                    .param(t.id()).query(Long.class).list().size();
            if (others == 0) return "This is the only active Administrator account, so it cannot be deleted.";
        }
        return null;
    }

    private List<Appraisal> appraisals(long userId) {
        return jdbc.sql("""
                SELECT a.id,
                       (EXISTS (SELECT 1 FROM review_actions r WHERE r.appraisal_id = a.id)
                        OR EXISTS (SELECT 1 FROM appraisal_reports p WHERE p.appraisal_id = a.id)
                        OR EXISTS (SELECT 1 FROM appraisal_messages m WHERE m.appraisal_id = a.id)) AS locked
                FROM appraisals a JOIN faculty_profiles fp ON fp.id = a.faculty_id
                WHERE fp.user_id = ? ORDER BY a.id""")
                .param(userId)
                .query((rs, n) -> new Appraisal(rs.getLong(1), rs.getBoolean(2))).list();
    }

    private int retainedReferences(long id) {
        return jdbc.sql(KEPT_REFERENCES).param("id", id).query(Integer.class).single();
    }
}
