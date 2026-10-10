package edu.svec.fams.appraisal;

import edu.svec.fams.auth.FamsUserPrincipal;
import edu.svec.fams.auth.Role;
import edu.svec.fams.common.ApiException;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The single place that decides who may see or edit an appraisal. Everything that touches appraisal
 * content (workflow, sections, scores) goes through here, so the rules cannot drift apart.
 */
@Component
public class AppraisalAccess {

    /**
     * @param queryRaised the Head of the Department is reviewing it and has sent a message the faculty member has not
     *                    yet answered by sending the appraisal again
     */
    public record Core(long id, long ownerUserId, long departmentId, AppraisalStatus status, boolean queryRaised) {
        /**
         * A draft, or an appraisal under the Head of the Department's review with a query open: from the message until the
         * Head of the Department approves, the faculty member may correct it and send it again.
         */
        public boolean editableByFaculty() {
            return status.isEditableByFaculty() || (status == AppraisalStatus.HOD_REVIEW && queryRaised);
        }
    }

    /**
     * The Principal and the Director Technical, who stand at the same level, see an appraisal only once the Head of the
     * Department has forwarded it, and from then on for good. The HoD sees everything but a draft.
     */
    private static final Set<AppraisalStatus> FINAL_LEVEL_VISIBLE = Collections.unmodifiableSet(EnumSet.of(
            AppraisalStatus.HOD_APPROVED, AppraisalStatus.PRINCIPAL_REVIEW, AppraisalStatus.APPROVED));

    /** The statuses at which the Principal or the Director Technical may open an appraisal. */
    public static Set<AppraisalStatus> finalLevelVisible() { return FINAL_LEVEL_VISIBLE; }

    private final JdbcClient jdbc;

    public AppraisalAccess(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** Loads the appraisal only if this user may see it; otherwise behaves as if it does not exist (404). */
    public Core loadVisible(long id, FamsUserPrincipal user) {
        return load(id, user, false);
    }

    /**
     * For writes. Must run inside a transaction: the appraisal row is locked until it ends, so an edit
     * can never interleave with a status change (e.g. a save racing a submit).
     * 403 if the viewer is not the author, 409 if the appraisal is locked for editing.
     */
    public Core loadEditable(long id, FamsUserPrincipal user) {
        Core core = load(id, user, true);
        if (user.role() != Role.FACULTY) throw ApiException.forbidden("Only the faculty member can edit an appraisal.");
        if (!core.editableByFaculty()) {
            throw ApiException.conflict("This appraisal is locked while it is " + core.status().name() + ".");
        }
        return core;
    }

    private Core load(long id, FamsUserPrincipal user, boolean lock) {
        Core core = jdbc.sql("""
                SELECT a.id, fp.user_id AS owner, fp.department_id AS dept, a.status,
                       (a.status = 'HOD_REVIEW' AND EXISTS (SELECT 1 FROM appraisal_messages m WHERE m.appraisal_id = a.id AND m.answered_at IS NULL)) AS raised
                FROM appraisals a JOIN faculty_profiles fp ON fp.id = a.faculty_id
                WHERE a.id = ?""" + (lock ? " FOR UPDATE OF a" : ""))
                .param(id)
                .query((rs, n) -> new Core(rs.getLong("id"), rs.getLong("owner"), rs.getLong("dept"),
                        AppraisalStatus.valueOf(rs.getString("status")), rs.getBoolean("raised")))
                .optional()
                .orElseThrow(ApiException::notFound);

        boolean visible = switch (user.role()) {
            case FACULTY -> core.ownerUserId() == user.id();
            // A draft is private to its author until submitted.
            case HOD -> core.status() != AppraisalStatus.DRAFT
                    && jdbc.sql("SELECT COUNT(*) FROM hod_assignments WHERE user_id = ? AND department_id = ?")
                            .params(user.id(), core.departmentId()).query(Long.class).single() > 0;
            case PRINCIPAL, DIRECTOR -> FINAL_LEVEL_VISIBLE.contains(core.status());
            case ADMIN -> false; // ADMIN manages configuration, not appraisal content
        };
        if (!visible) throw ApiException.notFound();
        return core;
    }
}
