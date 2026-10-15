package edu.svec.fams.appraisal;

import edu.svec.fams.audit.AuditService;
import edu.svec.fams.auth.FamsUserPrincipal;
import edu.svec.fams.auth.Role;
import edu.svec.fams.common.ApiException;
import edu.svec.fams.common.GeneratedKeys;
import edu.svec.fams.scoring.ScoreService;
import edu.svec.fams.section.FieldSpec;
import edu.svec.fams.section.Sections;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AppraisalService {

    public record HistoryRow(String action, String fromStatus, String toStatus, String actorRole,
                             String comment, OffsetDateTime at) {}

    /**
     * A revocation as the reader is allowed to see it: who revoked (by role), the remarks given and when. The faculty member
     * is shown the Head of the Department's revocations only; the HoD sees those of the Principal and the Director Technical
     * that sent an appraisal back to them.
     */
    public record RevokeNotice(String byRole, String remarks, OffsetDateTime at) {}

    /** One line of the revoke and resubmit trail the HoD sees: a submission, a resubmission or a revocation. */
    public record TrailEntry(String action, String actorRole, String actorName, String remarks, OffsetDateTime at) {}

    /** @param timesRevoked revocations by anyone; @param timesResent times the faculty member sent it again after the first submission */
    public record RevocationTrail(long appraisalId, int timesRevoked, int timesResent, List<TrailEntry> entries) {}

    /** Review actions that are revocations. The text of these names is stored in review_actions.action. */
    static final Set<String> REVOKE_ACTIONS = Set.of("HOD_REVOKE", "PRINCIPAL_REVOKE", "DIRECTOR_REVOKE");

    /** What the Principal and the Director Technical are shown of the history: their own level and the HoD's forwarding. */
    private static final Set<String> FINAL_LEVEL_HISTORY = Set.of("HOD_APPROVE", "START_PRINCIPAL_REVIEW", "PRINCIPAL_APPROVE",
            "START_DIRECTOR_REVIEW", "DIRECTOR_APPROVE", "PRINCIPAL_REVOKE", "DIRECTOR_REVOKE");

    /**
     * What the faculty member's cadre is asked to teach, for the help shown beside the course list: courses a year and a
     * semester, the marks each earns and the most the workload part is worth. Null for an appraisal begun under the older
     * rule, which has no such requirement.
     */
    public record CourseLoad(int perYear, int perSemester, BigDecimal marksPerCourse, int workloadMax) {}

    /**
     * Part A identity as the form prints it: name, employee ID, department, designation (cadre), e-mail.
     *
     * @param history the review history, for the reviewers and the administrator; {@code null}, and so absent from the
     *                response, for the faculty member, who is never shown it
     */
    public record AppraisalView(long id, String status, String academicYear, String facultyName,
                                String employeeId, String email,
                                String department, String cadre, boolean editable,
                                OffsetDateTime submittedAt, OffsetDateTime finalApprovedAt,
                                OffsetDateTime declaredAt,
                                List<ScoreService.ScoreRow> scores,
                                @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                                List<HistoryRow> history,
                                List<String> submitBlockers, boolean queryRaised,
                                LocalDate academicYearStart, LocalDate academicYearEnd, CourseLoad courseLoad,
                                @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                                RevokeNotice returnedNotice,
                                @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                                List<RevokeNotice> revocations,
                                @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                                Integer timesRevoked,
                                @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                                Integer timesResent) {}

    private record Head(String academicYear, LocalDate academicYearStart, LocalDate academicYearEnd, String facultyName, String employeeId, String email, String department,
                          String cadre, OffsetDateTime submittedAt, OffsetDateTime finalApprovedAt,
                          OffsetDateTime declaredAt) {}

    static final int MAX_COMMENT_LENGTH = 2000; // matches review_actions.comment

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final AppraisalAccess access;
    private final ScoreService scoreService;

    public AppraisalService(JdbcClient jdbc, AuditService audit, AppraisalAccess access, ScoreService scoreService) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.access = access;
        this.scoreService = scoreService;
    }

    @Transactional
    public long create(FamsUserPrincipal user) {
        if (user.role() != Role.FACULTY) throw ApiException.forbidden("Only faculty can start an appraisal.");

        var profile = jdbc.sql("SELECT id, cadre_id FROM faculty_profiles WHERE user_id = ?")
                .param(user.id())
                .query((rs, n) -> new long[] {rs.getLong("id"), rs.getLong("cadre_id")})
                .optional()
                .orElseThrow(() -> ApiException.conflict("Your faculty profile has not been set up yet."));

        long yearId = jdbc.sql("SELECT id FROM academic_years WHERE active = TRUE ORDER BY start_date DESC LIMIT 1")
                .query(Long.class).optional()
                .orElseThrow(() -> ApiException.conflict("No active academic year is configured."));

        long policyId = jdbc.sql("""
                SELECT id FROM scoring_policies
                WHERE academic_year_id = ? AND cadre_id = ? AND active = TRUE
                ORDER BY version DESC LIMIT 1""")
                .params(yearId, profile[1])
                .query(Long.class).optional()
                .orElseThrow(() -> ApiException.conflict("No scoring policy exists for your cadre this year."));

        long id;
        try {
            id = GeneratedKeys.insert(jdbc.sql("INSERT INTO appraisals (faculty_id, academic_year_id, scoring_policy_id) VALUES (?,?,?)")
                    .params(profile[0], yearId, policyId));
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("An appraisal already exists for this academic year.");
        }

        // Snapshot maximum marks so later policy changes never alter this appraisal.
        jdbc.sql("""
                INSERT INTO appraisal_scores (appraisal_id, criterion, max_marks)
                SELECT ?, criterion, max_marks FROM scoring_policy_criteria WHERE policy_id = ?""")
                .params(id, policyId)
                .update();

        // Part A starts from the profile; the faculty member can correct it while the appraisal is a draft.
        jdbc.sql("""
                INSERT INTO general_information (appraisal_id, contact_no, qualification_specialization, phd_status,
                    joining_date_institution, joining_date_designation, teaching_experience_years,
                    teaching_experience_months, industry_experience_years, research_experience_years)
                SELECT ?, contact_no, NULLIF(CONCAT_WS(', ', qualification, specialization), ''), phd_status,
                       joining_date_institution, joining_date_designation,
                       LEAST(40, FLOOR(teaching_experience_years)),
                       CASE WHEN teaching_experience_years >= 40 THEN 0
                            ELSE LEAST(11, ROUND((teaching_experience_years - FLOOR(teaching_experience_years)) * 12)) END,
                       industry_experience_years, research_experience_years
                FROM faculty_profiles WHERE id = ?""")
                .params(id, profile[0])
                .update();

        // the profile's research IDs become one row each (a kind that was left empty is not added)
        jdbc.sql("""
                INSERT INTO research_profiles (appraisal_id, platform, profile_id)
                SELECT ?, k.platform, k.value FROM (
                  SELECT 'ORCID' AS platform, NULLIF(TRIM(orcid), '') AS value FROM faculty_profiles WHERE id = ?
                  UNION ALL SELECT 'SCOPUS', NULLIF(TRIM(scopus_id), '') FROM faculty_profiles WHERE id = ?
                  UNION ALL SELECT 'GOOGLE_SCHOLAR', NULLIF(TRIM(google_scholar_id), '') FROM faculty_profiles WHERE id = ?
                  UNION ALL SELECT 'VIDWAN', NULLIF(TRIM(vidwan_id), '') FROM faculty_profiles WHERE id = ?
                ) k WHERE k.value IS NOT NULL""")
                .params(id, profile[0], profile[0], profile[0], profile[0])
                .update();

        audit.record(user.id(), "APPRAISAL_CREATED", "APPRAISAL", id, null);
        return id;
    }

    /**
     * @param queryRaised the HoD is reviewing it and has sent the faculty member a message (see {@link MessageService})
     * @param revoked it is a draft again because the HoD revoked it: the faculty member is to correct it and send it again
     * @param facultyId the faculty member's record, which the HoD's spreadsheet downloads are asked about
     */
    public record ListItem(long id, String facultyName, String employeeId, String department,
                           String academicYear, String status, OffsetDateTime submittedAt,
                           OffsetDateTime updatedAt, boolean queryRaised, boolean revoked, long facultyId) {}

    /** SQL for "under HoD review and a message has been sent about it", for an appraisals row aliased {@code a}. */
    static final String QUERY_RAISED_SQL = "(a.status = 'HOD_REVIEW' AND EXISTS (SELECT 1 FROM appraisal_messages m WHERE m.appraisal_id = a.id AND m.answered_at IS NULL))";

    /** SQL for "a draft again because the HoD revoked it" (its latest review step is the revoke), for an appraisals row aliased {@code a}. */
    public static final String REVOKED_SQL = "(a.status = 'DRAFT' AND (SELECT r.action FROM review_actions r "
            + "WHERE r.appraisal_id = a.id ORDER BY r.id DESC LIMIT 1) = 'HOD_REVOKE')";

    static final int MAX_LIST = 200;

    /** 'A','B' for SQL: the names come from the enum, never from a request. */
    static String statusList(Set<AppraisalStatus> statuses) {
        return statuses.stream().map(s -> "'" + s.name() + "'").collect(Collectors.joining(","));
    }

    /**
     * The appraisals this user may open: a faculty member's own; an HoD's department (submitted ones only); the
     * Principal's or Director Technical's (from the HoD's approval onward). Newest activity first.
     */
    @Transactional(readOnly = true)
    public List<ListItem> list(FamsUserPrincipal user) {
        String where;
        List<Object> params = new ArrayList<>();
        switch (user.role()) {
            case FACULTY -> {
                where = "fp.user_id = ?";
                params.add(user.id());
            }
            case HOD -> {
                where = "a.status <> 'DRAFT' AND fp.department_id IN "
                        + "(SELECT department_id FROM hod_assignments WHERE user_id = ?)";
                params.add(user.id());
            }
            case PRINCIPAL, DIRECTOR -> where = "a.status IN (" + statusList(AppraisalAccess.finalLevelVisible()) + ")";
            default -> throw ApiException.forbidden("Your role has no appraisals to list.");
        }
        return jdbc.sql("SELECT a.id, fp.name, fp.employee_id, d.name AS dept, ay.name AS ay, a.status, "
                + "a.submitted_at, a.updated_at, " + QUERY_RAISED_SQL + ", " + REVOKED_SQL + ", fp.id AS fid " + """
                FROM appraisals a
                JOIN faculty_profiles fp ON fp.id = a.faculty_id
                JOIN users fu ON fu.id = fp.user_id AND fu.deleted_at IS NULL
                JOIN departments d ON d.id = fp.department_id
                JOIN academic_years ay ON ay.id = a.academic_year_id
                """ + " WHERE " + where + " ORDER BY a.updated_at DESC, a.id DESC LIMIT " + MAX_LIST)
                .params(params)
                .query((rs, n) -> new ListItem(rs.getLong("id"), rs.getString("name"), rs.getString("employee_id"),
                        rs.getString("dept"), rs.getString("ay"), rs.getString("status"),
                        rs.getObject("submitted_at", OffsetDateTime.class),
                        rs.getObject("updated_at", OffsetDateTime.class), rs.getBoolean(9), rs.getBoolean(10), rs.getLong(11)))
                .list();
    }

    @Transactional(readOnly = true)
    public AppraisalView get(long id, FamsUserPrincipal user) {
        AppraisalAccess.Core core = access.loadVisible(id, user);

        Head head = jdbc.sql("""
                SELECT ay.name AS ay, ay.start_date AS ay_start, ay.end_date AS ay_end, fp.name AS fname, fp.employee_id, u.email, d.name AS dept, c.name AS cadre,
                       a.submitted_at, a.final_approved_at, a.declared_at
                FROM appraisals a
                JOIN academic_years ay ON ay.id = a.academic_year_id
                JOIN faculty_profiles fp ON fp.id = a.faculty_id
                JOIN users u ON u.id = fp.user_id
                JOIN departments d ON d.id = fp.department_id
                JOIN cadres c ON c.id = fp.cadre_id
                WHERE a.id = ?""")
                .param(id)
                .query((rs, n) -> new Head(rs.getString("ay"), rs.getObject("ay_start", LocalDate.class),
                        rs.getObject("ay_end", LocalDate.class), rs.getString("fname"), rs.getString("employee_id"),
                        rs.getString("email"), rs.getString("dept"), rs.getString("cadre"),
                        rs.getObject("submitted_at", OffsetDateTime.class),
                        rs.getObject("final_approved_at", OffsetDateTime.class),
                        rs.getObject("declared_at", OffsetDateTime.class)))
                .single();

        List<ScoreService.ScoreRow> scores = scoreService.rows(id);

        // The review history (the steps taken and what the reviewers wrote) is for the reviewers, and each reviewer is shown
        // only what is theirs to see (see historyFor). The faculty member is told where the appraisal stands, is sent the
        // Head of the Department's messages and is shown the HoD's remarks when the appraisal is revoked; nothing more.
        List<HistoryRow> all = reviewHistory(id);
        List<HistoryRow> history = historyFor(user.role(), all);

        RevokeNotice returned = null;
        List<RevokeNotice> revocations = null;
        Integer timesRevoked = null;
        Integer timesResent = null;
        HistoryRow last = all.isEmpty() ? null : all.getLast();
        if (user.role() == Role.FACULTY) {
            revocations = all.stream().filter(h -> "HOD_REVOKE".equals(h.action()))
                    .map(h -> new RevokeNotice("HOD", h.comment(), h.at())).toList();
            if (core.status() == AppraisalStatus.DRAFT && last != null && "HOD_REVOKE".equals(last.action())) {
                returned = new RevokeNotice("HOD", last.comment(), last.at());
            }
        } else if (user.role() == Role.HOD) {
            // an appraisal the Principal or the Director Technical has sent back to this HoD
            if (core.status() == AppraisalStatus.HOD_REVIEW && last != null
                    && ("PRINCIPAL_REVOKE".equals(last.action()) || "DIRECTOR_REVOKE".equals(last.action()))) {
                returned = new RevokeNotice(last.actorRole(), last.comment(), last.at());
            }
            timesRevoked = (int) all.stream().filter(h -> REVOKE_ACTIONS.contains(h.action())).count();
            timesResent = timesResent(all);
        }

        boolean editable = user.role() == Role.FACULTY && core.editableByFaculty();
        List<String> blockers = editable ? submitBlockers(id) : List.of();
        return new AppraisalView(id, core.status().name(), head.academicYear(), head.facultyName(),
                head.employeeId(), head.email(), head.department(), head.cadre(), editable,
                head.submittedAt(), head.finalApprovedAt(), head.declaredAt(),
                scores, history, blockers, queryRaised(id), head.academicYearStart(), head.academicYearEnd(), courseLoad(id),
                returned, revocations, timesRevoked, timesResent);
    }

    /**
     * What each reader is shown of the review history. The faculty member none of it. The HoD all of it, except what the
     * Principal and the Director Technical wrote with an approval (their feedback is not the HoD's to read; the remarks
     * with which they send an appraisal back are). The Principal and the Director Technical see their own level and the HoD's
     * forwarding, never the HoD's revocations, whose remarks are for the faculty member.
     */
    static List<HistoryRow> historyFor(Role role, List<HistoryRow> all) {
        return switch (role) {
            case FACULTY, ADMIN -> null;
            case HOD -> all.stream().map(h -> isFinalLevelFeedback(h)
                    ? new HistoryRow(h.action(), h.fromStatus(), h.toStatus(), h.actorRole(), null, h.at()) : h).toList();
            case PRINCIPAL, DIRECTOR -> all.stream().filter(h -> FINAL_LEVEL_HISTORY.contains(h.action())).toList();
        };
    }

    private static boolean isFinalLevelFeedback(HistoryRow h) {
        return ("PRINCIPAL".equals(h.actorRole()) || "DIRECTOR".equals(h.actorRole())) && !REVOKE_ACTIONS.contains(h.action());
    }

    /** Times the faculty member sent it again: every submission after the first, and every correction under review. */
    static int timesResent(List<HistoryRow> all) {
        long submits = all.stream().filter(h -> "SUBMIT".equals(h.action())).count();
        long resubmits = all.stream().filter(h -> "RESUBMIT".equals(h.action())).count();
        return (int) (Math.max(0, submits - 1) + resubmits);
    }

    /**
     * The revoke and resubmit trail of one appraisal, for the Head of the Department of the faculty member's department. It
     * works for a revoked appraisal that is a draft again (which the HoD cannot open): it carries the steps and the remarks,
     * never the content of the appraisal.
     */
    @Transactional(readOnly = true)
    public RevocationTrail revocationTrail(long id, FamsUserPrincipal user) {
        if (user.role() != Role.HOD) throw ApiException.forbidden("Only the Head of the Department sees the revocation trail.");
        Long dept = jdbc.sql("SELECT fp.department_id FROM appraisals a JOIN faculty_profiles fp ON fp.id = a.faculty_id "
                        + "JOIN users fu ON fu.id = fp.user_id AND fu.deleted_at IS NULL WHERE a.id = ?")
                .param(id).query(Long.class).optional().orElseThrow(ApiException::notFound);
        long assigned = jdbc.sql("SELECT COUNT(*) FROM hod_assignments WHERE user_id = ? AND department_id = ?")
                .params(user.id(), dept).query(Long.class).single();
        if (assigned == 0) throw ApiException.notFound();

        List<TrailEntry> entries = jdbc.sql("""
                SELECT r.action, r.actor_role, u.name, r.comment, r.created_at
                FROM review_actions r LEFT JOIN users u ON u.id = r.actor_id
                WHERE r.appraisal_id = ? AND r.action IN ('SUBMIT','RESUBMIT','HOD_REVOKE','PRINCIPAL_REVOKE','DIRECTOR_REVOKE')
                ORDER BY r.id""")
                .param(id)
                .query((rs, n) -> new TrailEntry(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getObject(5, OffsetDateTime.class)))
                .list();
        List<HistoryRow> asHistory = entries.stream()
                .map(e -> new HistoryRow(e.action(), null, null, e.actorRole(), e.remarks(), e.at())).toList();
        int revoked = (int) entries.stream().filter(e -> REVOKE_ACTIONS.contains(e.action())).count();
        return new RevocationTrail(id, revoked, timesResent(asHistory), entries);
    }

    private CourseLoad courseLoad(long id) {
        return jdbc.sql("""
                SELECT p.courses_per_year,
                       (SELECT pc.max_marks FROM scoring_policy_components pc
                        WHERE pc.policy_id = p.id AND pc.criterion = 'TEACHING_LEARNING' AND LOWER(pc.description) LIKE 'workload%'
                        ORDER BY pc.sort_order LIMIT 1)
                FROM appraisals a JOIN scoring_policies p ON p.id = a.scoring_policy_id WHERE a.id = ?""")
                .param(id)
                .query((rs, n) -> {
                    Integer perYear = (Integer) rs.getObject(1);
                    Integer max = (Integer) rs.getObject(2);
                    if (perYear == null || perYear <= 0 || max == null) return null;
                    return new CourseLoad(perYear, Math.max(1, perYear / 2),
                            BigDecimal.valueOf(max).divide(BigDecimal.valueOf(perYear), 2, java.math.RoundingMode.HALF_UP).stripTrailingZeros(), max);
                }).optional().orElse(null);
    }

    private boolean queryRaised(long id) {
        return jdbc.sql("SELECT " + QUERY_RAISED_SQL + " FROM appraisals a WHERE a.id = ?").param(id).query(Boolean.class).single();
    }

    /**
     * Every step of the review with the comment recorded at it, oldest first. This is the unredacted record, for the
     * printed report; {@link #get} withholds the comments from the faculty member.
     */
    @Transactional(readOnly = true)
    public List<HistoryRow> reviewHistory(long id) {
        return jdbc.sql("""
                SELECT action, from_status, to_status, actor_role, comment, created_at
                FROM review_actions WHERE appraisal_id = ? ORDER BY id""")
                .param(id)
                .query((rs, n) -> new HistoryRow(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getObject(6, OffsetDateTime.class)))
                .list();
    }

    /**
     * What must be filled in before the appraisal can go to the HoD: the least that makes it reviewable. The college has
     * not said what is mandatory, so this is a working minimum, easy to change here: the Part A details a reviewer
     * needs to identify and place the person (the date of promotion is optional). Everything else may be left empty.
     */
    List<String> submitBlockers(long id) {
        List<String> missing = new ArrayList<>();
        var part = jdbc.sql("SELECT contact_no, qualification_specialization, joining_date_institution, teaching_experience_years FROM general_information WHERE appraisal_id = ?")
                .param(id).query((rs, n) -> new Object[] {rs.getString(1), rs.getString(2), rs.getObject(3), rs.getObject(4)}).optional().orElse(null);
        if (part == null || part[0] == null || part[1] == null || part[2] == null || part[3] == null) {
            missing.add("General Information: contact number, qualification and specialization, date of joining the institution, and teaching experience");
        }
        // a journal paper entered before the DOI or ISSN became mandatory is named here, not left to be found at review
        int unreachable = 0;
        for (String doi : jdbc.sql("SELECT doi_issn FROM journal_publications WHERE appraisal_id = ?").param(id).query(String.class).list()) {
            if (!FieldSpec.hasValidDoiOrIssn(doi)) unreachable++;
        }
        if (unreachable > 0) {
            missing.add("Journal publications: give a valid DOI or ISSN for " + (unreachable == 1 ? "the paper" : "each of the " + unreachable + " papers") + " that lack one");
        }
        String plan = jdbc.sql("SELECT plan_text FROM action_plan WHERE appraisal_id = ?").param(id)
                .query(String.class).optional().orElse(null);
        int words = FieldSpec.countWords(plan);
        if (words == 0) {
            missing.add("Action Plan for the Next 2 Years: write your plan (at most " + String.format("%,d", Sections.MAX_ACTION_PLAN_WORDS) + " words)");
        } else if (words > Sections.MAX_ACTION_PLAN_WORDS) {
            missing.add("Action Plan for the Next 2 Years: shorten your plan to " + String.format("%,d", Sections.MAX_ACTION_PLAN_WORDS) + " words or fewer");
        }
        return missing;
    }

    /** The API's submit: refuses an appraisal that is missing what {@link #submitBlockers} lists. */
    @Transactional
    public AppraisalStatus submitChecked(long id, FamsUserPrincipal user) {
        if (user.role() == Role.FACULTY) {
            access.loadVisible(id, user);
            List<String> missing = submitBlockers(id);
            if (!missing.isEmpty()) {
                throw ApiException.badRequest("Not ready to submit. Still needed: " + String.join(". ", missing) + ".");
            }
        }
        return submit(id, user);
    }

    /**
     * Submits with the form's Declaration: "the information furnished is true and correct ... and
     * supporting documents are available for verification"; the date is the server time.
     * This is the only way into SUBMITTED, so every submission carries a declaration.
     */
    @Transactional
    public AppraisalStatus submit(long id, FamsUserPrincipal user) {
        if (user.role() != Role.FACULTY) throw ApiException.forbidden("Only the faculty member can submit.");
        AppraisalAccess.Core core = access.loadVisible(id, user);
        if (!core.editableByFaculty()) {
            throw ApiException.conflict("This action is not available while the appraisal is "
                    + core.status().name() + ".");
        }
        jdbc.sql("UPDATE appraisals SET declared_at = CURRENT_TIMESTAMP WHERE id = ?").param(id).update();
        boolean again = core.status() != AppraisalStatus.DRAFT;
        AppraisalStatus status = move(id, user, again ? WorkflowAction.RESUBMIT : WorkflowAction.SUBMIT, core, null);
        // Sending it again answers the Head of the Department's messages: they stay on record but no longer keep it open.
        if (again) jdbc.sql("UPDATE appraisal_messages SET answered_at = CURRENT_TIMESTAMP WHERE appraisal_id = ? AND answered_at IS NULL").param(id).update();
        return status;
    }

    /** Review steps (start, approve). Submitting goes through {@link #submit}. */
    @Transactional
    public AppraisalStatus transition(long id, FamsUserPrincipal user, WorkflowAction.Verb verb, String comment) {
        if (verb == WorkflowAction.Verb.SUBMIT) throw new IllegalArgumentException("Use submit() for submissions");
        AppraisalAccess.Core core = access.loadVisible(id, user);
        WorkflowAction action = WorkflowAction.resolve(user.role(), verb);
        return move(id, user, action, core, comment);
    }

    /**
     * Revokes an appraisal one level back, at any stage before the final approval, with the reviewer's remarks. No one's
     * consent is needed.
     * <ul>
     *   <li>The Head of the Department's revoke (of an appraisal that is submitted, under review or already forwarded)
     *       returns it to the faculty member as a draft, with the HoD's remarks, to correct and send again.</li>
     *   <li>The Principal's or the Director Technical's revoke (of an appraisal the HoD forwarded) returns it to the HoD, with
     *       their remarks, for the HoD to review again.</li>
     * </ul>
     * The remarks are required. With the reviewer, the time and both statuses they are written to the audit trail and to
     * the review history, where each reader sees only the remarks that are theirs (see {@link #historyFor}). An approved
     * appraisal can never be revoked.
     */
    @Transactional
    public AppraisalStatus revoke(long id, FamsUserPrincipal user, String reason) {
        // not a step of the forward chain (WorkflowAction): the name is what review_actions records
        String action = switch (user.role()) {
            case HOD -> "HOD_REVOKE";
            case PRINCIPAL -> "PRINCIPAL_REVOKE";
            case DIRECTOR -> "DIRECTOR_REVOKE";
            case FACULTY, ADMIN -> throw ApiException.forbidden("Only a reviewer can revoke an appraisal.");
        };
        AppraisalAccess.Core core = access.loadVisible(id, user);   // refuses another department, and what is not yet forwarded
        String why = reason == null ? "" : reason.strip();
        if (why.isEmpty()) {
            throw new edu.svec.fams.common.ValidationException(java.util.Map.of("reason", "Please give the remarks for revoking."));
        }
        if (why.length() > MAX_COMMENT_LENGTH) {
            throw new edu.svec.fams.common.ValidationException(java.util.Map.of("reason",
                    "The remarks must be at most " + MAX_COMMENT_LENGTH + " characters."));
        }
        AppraisalStatus from = core.status();
        if (from == AppraisalStatus.APPROVED) {
            throw ApiException.conflict("This appraisal has received its final approval and can no longer be revoked.");
        }
        AppraisalStatus to = from.revokeTarget(user.role());
        if (to == null) throw ApiException.conflict("This action is not available while the appraisal is " + from.name() + ".");

        int changed = jdbc.sql("UPDATE appraisals SET status = ? WHERE id = ? AND status = ?")
                .params(to.name(), id, from.name()).update();
        if (changed == 0) throw ApiException.conflict("The appraisal was changed by someone else. Please reload.");

        jdbc.sql("""
                INSERT INTO review_actions (appraisal_id, actor_id, actor_role, action, from_status, to_status, comment)
                VALUES (?,?,?,?,?,?,?)""")
                .params(id, user.id(), user.role().name(), action, from.name(), to.name(), why).update();
        // an open question to the faculty member is closed with the review it belonged to
        jdbc.sql("UPDATE appraisal_messages SET answered_at = CURRENT_TIMESTAMP WHERE appraisal_id = ? AND answered_at IS NULL")
                .param(id).update();
        java.util.Map<String, Object> details = new java.util.LinkedHashMap<>();
        if (user.role() == Role.HOD) details.put("hodId", user.id());
        details.put("revokedBy", user.role().name());
        details.put("revokerId", user.id());
        details.put("at", java.time.OffsetDateTime.now().toString());
        details.put("reason", why);
        details.put("fromStatus", from.name());
        details.put("toStatus", to.name());
        audit.recordDetails(user.id(), "APPRAISAL_REVOKED", "APPRAISAL", id, details);
        return to;
    }

    private AppraisalStatus move(long id, FamsUserPrincipal user, WorkflowAction action,
                                 AppraisalAccess.Core core, String comment) {
        if (comment != null && comment.length() > MAX_COMMENT_LENGTH) {
            throw ApiException.badRequest("The comment must be at most " + MAX_COMMENT_LENGTH + " characters.");
        }
        if (!core.status().canTransitionTo(action.target())) {
            throw ApiException.conflict("This action is not available while the appraisal is "
                    + core.status().name() + ".");
        }

        // Guarded update: if another request already moved the appraisal, no row matches.
        int changed = jdbc.sql("""
                UPDATE appraisals SET status = ?,
                  submitted_at = CASE WHEN ? = 'SUBMITTED' THEN CURRENT_TIMESTAMP ELSE submitted_at END,
                  final_approved_at = CASE WHEN ? = 'APPROVED' THEN CURRENT_TIMESTAMP ELSE final_approved_at END
                WHERE id = ? AND status = ?""")
                .params(action.target().name(), action.target().name(), action.target().name(),
                        id, core.status().name())
                .update();
        if (changed == 0) throw ApiException.conflict("The appraisal was changed by someone else. Please reload.");

        jdbc.sql("""
                INSERT INTO review_actions (appraisal_id, actor_id, actor_role, action, from_status, to_status, comment)
                VALUES (?,?,?,?,?,?,?)""")
                .params(id, user.id(), user.role().name(), action.name(),
                        core.status().name(), action.target().name(),
                        comment == null || comment.isBlank() ? null : comment.trim())
                .update();

        audit.record(user.id(), "APPRAISAL_" + action.name(), "APPRAISAL", id, null);
        return action.target();
    }
}
