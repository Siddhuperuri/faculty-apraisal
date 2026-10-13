package edu.svec.fams.report;

import edu.svec.fams.audit.AuditService;
import edu.svec.fams.auth.FamsUserPrincipal;
import edu.svec.fams.auth.Role;
import edu.svec.fams.common.ApiException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The appraisal details of chosen faculty as one spreadsheet (or CSV file), one row per faculty member, in the layout the
 * colleges use for the department review: academics, certifications, FDPs attended and organised, publications and patents,
 * contributions, the status, the submission date and the HoD's remarks.
 *
 * <p>Who may have what is decided here, on the server, from the chosen ids and the caller's role, never from the page:
 * <ul>
 *   <li>a Head of the Department may export only faculty of a department assigned to them; one id outside those
 *       departments makes the whole request "not found" (nothing is exported, and the answer is the same as for an id that
 *       does not exist);</li>
 *   <li>an administrator may export any faculty member's appraisal details (this replaces the earlier rule that kept
 *       administrators out of appraisal content);</li>
 *   <li>a draft stays private to its author: a faculty member who has not submitted is listed, with the status "Not
 *       submitted" and "—" in every content column, whoever asks.</li>
 * </ul>
 * Nothing about a password, a reset link, a session or any other security field is read, and every export is audited.
 *
 * <p>Queries are batched: one for the faculty and one for each kind of entry, however many rows are chosen.
 */
@Service
public class AppraisalExportService {

    /** The columns, as the department review sheet names them. */
    static final List<String> HEADERS = List.of(
            "S.No.", "Faculty Name", "Employee ID", "Department", "Cadre", "Date of Joining", "Academics",
            "Certifications", "FDPs Attended", "FDPs/Guest Lectures/Workshops Organized",
            "Journals/Conferences/Patents", "Contributions/Remarks",
            "Appraisal Status", "Submitted Date", "HoD Remarks", "Action Plan");

    /** What a cell with nothing to show contains. */
    static final String NONE = "—";

    private static final ZoneId COLLEGE_ZONE = ZoneId.of("Asia/Kolkata");

    private record Faculty(long id, String name, String employeeId, long departmentId, String departmentCode, String department,
                           String cadre, LocalDate joined, Long appraisalId, String status, OffsetDateTime submittedAt) {
        /** A draft is private to its author, and a faculty member with no appraisal has none to show. */
        boolean visible() { return appraisalId != null && status != null && !"DRAFT".equals(status); }
    }

    private final JdbcClient jdbc;
    private final AuditService audit;

    public AppraisalExportService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional
    public ExportFile export(FamsUserPrincipal actor, ExportRequest request) {
        if (actor.role() != Role.HOD && actor.role() != Role.ADMIN) throw ApiException.forbidden("Not allowed.");

        Object[] year = year(request.academicYearId());
        long yearId = (Long) year[0];
        String yearName = (String) year[1];

        List<Faculty> faculty = jdbc.sql("""
                SELECT fp.id, fp.name, fp.employee_id, d.id AS dept_id, d.code, d.name AS dept, c.name AS cadre,
                       COALESCE(gi.joining_date_institution, fp.joining_date_institution) AS joined,
                       fp.joining_date_institution AS profile_joined,
                       a.id AS aid, a.status, a.submitted_at
                FROM faculty_profiles fp
                JOIN users fu ON fu.id = fp.user_id AND fu.deleted_at IS NULL
                JOIN departments d ON d.id = fp.department_id
                JOIN cadres c ON c.id = fp.cadre_id
                LEFT JOIN appraisals a ON a.faculty_id = fp.id AND a.academic_year_id = :year
                LEFT JOIN general_information gi ON gi.appraisal_id = a.id AND a.status <> 'DRAFT'
                WHERE fp.id IN (:ids)""")
                .param("year", yearId).param("ids", request.ids())
                .query((rs, n) -> new Faculty(rs.getLong("id"), rs.getString("name"), rs.getString("employee_id"),
                        rs.getLong("dept_id"), rs.getString("code"), rs.getString("dept"), rs.getString("cadre"),
                        rs.getObject("joined", LocalDate.class), rs.getObject("aid", Long.class), rs.getString("status"),
                        rs.getObject("submitted_at", OffsetDateTime.class)))
                .list();

        // Every id must be a faculty member this caller may export; otherwise nothing is exported.
        if (faculty.size() != request.ids().size()) throw ApiException.notFound();
        if (actor.role() == Role.HOD) {
            Set<Long> mine = new LinkedHashSet<>(jdbc.sql("SELECT department_id FROM hod_assignments WHERE user_id = ?")
                    .param(actor.id()).query(Long.class).list());
            if (faculty.stream().anyMatch(f -> !mine.contains(f.departmentId()))) throw ApiException.notFound();
        }

        faculty = new ArrayList<>(faculty);
        faculty.sort(java.util.Comparator.comparing(Faculty::departmentCode).thenComparing(f -> f.name().toLowerCase(Locale.ROOT)));

        List<Long> appraisalIds = faculty.stream().filter(Faculty::visible).map(Faculty::appraisalId).toList();
        Content content = Content.load(jdbc, appraisalIds);

        List<List<Object>> rows = new ArrayList<>();
        int serial = 0;
        for (Faculty f : faculty) {
            boolean visible = f.visible();
            Long a = f.appraisalId();
            // null-tolerant list: a missing value is shown as NONE, never an exception
            rows.add(java.util.Arrays.asList(
                    ++serial, text(f.name()), text(f.employeeId()), text(f.department()), text(f.cadre()),
                    f.joined() == null ? NONE : f.joined(),
                    visible ? joined(content.academics.get(a)) : NONE,
                    visible ? joined(content.certifications.get(a)) : NONE,
                    visible ? joined(content.fdpsAttended.get(a)) : NONE,
                    visible ? joined(content.organised.get(a)) : NONE,
                    visible ? joined(content.research.get(a)) : NONE,
                    visible ? joined(content.contributions.get(a)) : NONE,
                    visible ? ReportLabels.statusLabel(f.status()) : "Not submitted",
                    visible && f.submittedAt() != null ? (Object) f.submittedAt().atZoneSameInstant(COLLEGE_ZONE).toLocalDate() : NONE,
                    visible ? content.hodRemarks.getOrDefault(a, NONE) : NONE,
                    visible ? content.actionPlans.getOrDefault(a, NONE) : NONE));
        }

        TreeSet<String> codes = new TreeSet<>();
        TreeSet<String> names = new TreeSet<>();
        for (Faculty f : faculty) {
            codes.add(f.departmentCode());
            names.add(f.department());
        }
        String scope = names.size() == 1 ? names.first() : names.size() <= 4 ? String.join(", ", codes) : names.size() + " departments";
        String title = "Faculty appraisal details - " + yearName + " - " + scope;
        // One title line and one header row: no subtitle, so the sheet is the title, the header and one row per faculty member.
        ExportTable table = new ExportTable("Appraisals", title, null, HEADERS, rows);
        byte[] bytes = request.format().render(table);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("kind", "APPRAISALS");
        details.put("format", request.format().name());
        details.put("academicYearId", yearId);
        details.put("academicYear", yearName);
        details.put("departments", new ArrayList<>(codes));
        details.put("selected", request.ids().size());
        details.put("withContent", appraisalIds.size());
        details.put("actorRole", actor.role().name());
        audit.recordDetails(actor.id(), "EXPORT_APPRAISALS", "APPRAISAL_EXPORT", null, details);

        String fileName = fileName(actor.role(), yearName, codes, request.format());
        return new ExportFile(fileName, request.format(), bytes, rows.size());
    }

    // ------------------------------------------------------------------ the year

    /** The year asked for, else the open year that begins latest, else the latest year; id and name. */
    private Object[] year(Long requested) {
        if (requested != null) {
            return jdbc.sql("SELECT id, name FROM academic_years WHERE id = ?").param(requested)
                    .query((rs, n) -> new Object[] {rs.getLong(1), rs.getString(2)}).optional().orElseThrow(ApiException::notFound);
        }
        return jdbc.sql("""
                SELECT id, name FROM academic_years
                ORDER BY active DESC, start_date DESC, id DESC LIMIT 1""")
                .query((rs, n) -> new Object[] {rs.getLong(1), rs.getString(2)}).optional()
                .orElseThrow(() -> ApiException.conflict("There is no academic year yet."));
    }

    // ------------------------------------------------------------------ cells

    private static String joined(List<String> lines) {
        if (lines == null || lines.isEmpty()) return NONE;
        if (lines.size() == 1) return lines.get(0);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(i + 1).append(". ").append(lines.get(i));
        }
        return sb.toString();
    }

    private static String text(String s) {
        return s == null || s.isBlank() ? NONE : s;
    }

    /** {@code HOD_Appraisal_Export_2025-26_CSE.xlsx}: who, what, the year and the department code(s). */
    static String fileName(Role role, String year, Set<String> departmentCodes, ExportFormat format) {
        String scope = departmentCodes.isEmpty() || departmentCodes.size() > 3 ? "Departments" : String.join("-", departmentCodes);
        return (role == Role.ADMIN ? "Admin" : "HOD") + "_Appraisal_Export_" + safe(year) + "_" + safe(scope) + "." + format.extension();
    }

    private static String safe(String s) {
        String v = s.replaceAll("[^A-Za-z0-9-]+", "-").replaceAll("^-+|-+$", "");
        return v.isEmpty() ? "All" : v;
    }

    // ------------------------------------------------------------------ the entries, a query per kind

    private interface Line { String of(ResultSet rs) throws SQLException; }

    /** Every kind of entry for the chosen appraisals, as readable lines grouped by appraisal. */
    private static final class Content {
        final Map<Long, List<String>> academics = new HashMap<>();
        final Map<Long, List<String>> certifications = new HashMap<>();
        final Map<Long, List<String>> fdpsAttended = new HashMap<>();
        final Map<Long, List<String>> organised = new HashMap<>();
        final Map<Long, List<String>> research = new HashMap<>();
        final Map<Long, List<String>> contributions = new HashMap<>();
        final Map<Long, String> hodRemarks = new HashMap<>();
        final Map<Long, String> actionPlans = new HashMap<>();

        static Content load(JdbcClient jdbc, List<Long> ids) {
            Content c = new Content();
            if (ids.isEmpty()) return c;

            c.add(jdbc, ids, c.academics, "SELECT * FROM teaching_courses WHERE appraisal_id IN (:ids) ORDER BY id", rs ->
                    rs.getString("course_code") + " - " + rs.getString("course_name") + " (" + ReportLabels.label(rs.getString("course_type"))
                            + "; " + ReportLabels.label(rs.getString("program")) + " " + ReportLabels.label(rs.getString("branch")) + ", semester " + rs.getInt("semester")
                            + opt(", section ", rs.getString("class_section"))
                            + "; " + ReportLabels.number(rs.getBigDecimal("hours_per_week")) + " h/week; pass "
                            + ReportLabels.number(rs.getBigDecimal("pass_percentage")) + "%"
                            + feedback(rs.getBigDecimal("phase1_feedback"), rs.getBigDecimal("phase2_feedback"))
                            + (rs.getString("course_role") == null ? "" : "; " + ReportLabels.label(rs.getString("course_role"))) + ")");
            c.add(jdbc, ids, c.academics, "SELECT appraisal_id, total_students_mentored FROM student_mentoring WHERE appraisal_id IN (:ids) AND total_students_mentored > 0",
                    rs -> "Students mentored: " + rs.getInt("total_students_mentored"));
            c.add(jdbc, ids, c.academics, "SELECT * FROM student_projects WHERE appraisal_id IN (:ids) ORDER BY id", rs ->
                    "Student project guided (" + ReportLabels.label(rs.getString("level")) + "): " + rs.getString("title") + "; "
                            + rs.getInt("student_count") + " student" + (rs.getInt("student_count") == 1 ? "" : "s") + "; outcome "
                            + ReportLabels.label(rs.getString("outcome")));
            c.add(jdbc, ids, c.academics, "SELECT * FROM student_achievements WHERE appraisal_id IN (:ids) ORDER BY id", rs ->
                    "Student achievement: " + rs.getString("student_name") + " (" + rs.getString("roll_no") + ") - " + rs.getString("achievement")
                            + "; " + ReportLabels.label(rs.getString("level")) + "; " + ReportLabels.monthYear(rs.getString("month_year")));

            c.add(jdbc, ids, c.certifications, "SELECT * FROM certifications WHERE appraisal_id IN (:ids) ORDER BY id", rs -> {
                String platform = rs.getString("platform");
                String other = rs.getString("platform_other");
                String name = "OTHER".equals(platform) && other != null && !other.isBlank() ? other : ReportLabels.label(platform);
                Integer weeks = (Integer) rs.getObject("duration_weeks");
                String duration = weeks != null ? weeks + " week" + (weeks == 1 ? "" : "s") : rs.getString("duration_weeks_hours");
                return name + ": " + rs.getString("title") + paren(period(rs.getObject("start_date", LocalDate.class),
                        rs.getObject("end_date", LocalDate.class)), duration, rs.getString("grade_score") == null ? null : "grade " + rs.getString("grade_score"));
            });

            c.add(jdbc, ids, c.fdpsAttended, "SELECT * FROM fdps WHERE appraisal_id IN (:ids) ORDER BY id", rs ->
                    rs.getString("title") + paren(ReportLabels.label(rs.getString("mode")), rs.getString("institution_venue"),
                            period(rs.getObject("start_date", LocalDate.class), rs.getObject("end_date", LocalDate.class)),
                            rs.getInt("days") + " day" + (rs.getInt("days") == 1 ? "" : "s")));

            c.add(jdbc, ids, c.organised, "SELECT * FROM events WHERE appraisal_id IN (:ids) ORDER BY id", rs ->
                    ReportLabels.label(rs.getString("activity_type")) + " - " + ReportLabels.label(rs.getString("role")) + ": " + rs.getString("title")
                            + paren(period(rs.getObject("start_date", LocalDate.class), rs.getObject("end_date", LocalDate.class)),
                            rs.getInt("beneficiaries") + " beneficiaries"));

            c.add(jdbc, ids, c.research, "SELECT * FROM journal_publications WHERE appraisal_id IN (:ids) ORDER BY id", rs ->
                    "Journal: " + rs.getString("title") + " - " + ReportLabels.label(rs.getString("author_position")) + "; " + rs.getString("journal")
                            + opt(", ", rs.getString("volume_issue_page")) + "; " + ReportLabels.monthYear(rs.getString("month_year")) + "; "
                            + ReportLabels.label(rs.getString("indexing")) + opt("; DOI/ISSN ", rs.getString("doi_issn")));
            c.add(jdbc, ids, c.research, "SELECT * FROM conference_papers WHERE appraisal_id IN (:ids) ORDER BY id", rs ->
                    "Conference: " + rs.getString("title") + " - " + rs.getString("conference") + " (" + ReportLabels.label(rs.getString("level"))
                            + "); " + ReportLabels.monthYear(rs.getString("month_year")) + opt("; ", rs.getString("venue"))
                            + opt("; indexed in ", rs.getString("doi_indexed_in")));
            c.add(jdbc, ids, c.research, "SELECT * FROM patents_ipr WHERE appraisal_id IN (:ids) ORDER BY id", rs ->
                    "Patent/IPR: " + rs.getString("title") + paren(ReportLabels.label(rs.getString("type")),
                            ReportLabels.label(rs.getString("status")),
                            rs.getString("application_patent_no") == null ? null : "no. " + rs.getString("application_patent_no"),
                            period(rs.getObject("record_date", LocalDate.class), null), rs.getString("applicant_inventors")));
            c.add(jdbc, ids, c.research, "SELECT * FROM books WHERE appraisal_id IN (:ids) ORDER BY id", rs ->
                    ReportLabels.label(rs.getString("type")) + ": " + rs.getString("title") + " - " + rs.getString("authors") + "; "
                            + rs.getString("publisher") + "; ISBN " + rs.getString("isbn") + "; " + ReportLabels.monthYear(rs.getString("month_year")));
            c.add(jdbc, ids, c.research, "SELECT * FROM funded_projects WHERE appraisal_id IN (:ids) ORDER BY id", rs ->
                    "Funded project: " + rs.getString("title") + " (" + ReportLabels.label(rs.getString("role")) + "; "
                            + ReportLabels.label(rs.getString("type")) + "; " + rs.getString("funding_agency_client") + "; "
                            + ("SANCTIONED".equals(rs.getString("status")) && rs.getBigDecimal("amount") != null
                                    ? "Rs. " + ReportLabels.number(rs.getBigDecimal("amount")) + "; " : "")
                            + ReportLabels.label(rs.getString("status")) + "; "
                            + rs.getInt("year") + ")");
            c.add(jdbc, ids, c.research, "SELECT * FROM research_scholars WHERE appraisal_id IN (:ids) ORDER BY id", rs ->
                    "Research scholar: " + rs.getString("name") + " (" + ReportLabels.label(rs.getString("degree")) + "; "
                            + ReportLabels.label(rs.getString("status")) + "; " + rs.getInt("year") + ")");

            c.add(jdbc, ids, c.contributions, """
                    SELECT h.appraisal_id, COALESCE(rv.label, h.role_code) AS label, h.remarks
                    FROM institutional_roles_held h
                    LEFT JOIN reference_values rv ON rv.list_code = 'INSTITUTIONAL_ROLE' AND rv.code = h.role_code AND rv.parent_code = ''
                    WHERE h.appraisal_id IN (:ids) ORDER BY h.id""", rs ->
                    "Institutional role: " + rs.getString("label") + opt(" - ", rs.getString("remarks")));
            c.add(jdbc, ids, c.contributions, "SELECT * FROM administrative_roles WHERE appraisal_id IN (:ids) ORDER BY id", rs ->
                    ReportLabels.label(rs.getString("scope")) + "-level role: " + rs.getString("role")
                            + paren(period(rs.getObject("from_date", LocalDate.class), rs.getObject("to_date", LocalDate.class)))
                            + opt(" - ", rs.getString("description")));
            c.add(jdbc, ids, c.contributions, "SELECT * FROM outreach WHERE appraisal_id IN (:ids) ORDER BY id", rs ->
                    "Outreach: " + rs.getString("role") + " - " + rs.getString("event_activity")
                            + paren(rs.getString("organization"), rs.getString("venue"), period(rs.getObject("event_date", LocalDate.class), null)));
            c.add(jdbc, ids, c.contributions, "SELECT * FROM memberships_awards WHERE appraisal_id IN (:ids) ORDER BY id", rs ->
                    "Membership/award: " + rs.getString("item") + " - " + rs.getString("awarding_body") + "; "
                            + ReportLabels.label(rs.getString("level")) + "; " + rs.getInt("year"));

            for (Object[] row : jdbc.sql("SELECT appraisal_id, plan_text FROM action_plan WHERE appraisal_id IN (:ids)").param("ids", ids)
                    .query((rs, n) -> new Object[] {rs.getLong(1), rs.getString(2)}).list()) {
                if (row[1] != null && !((String) row[1]).isBlank()) c.actionPlans.put((Long) row[0], ((String) row[1]).strip());
            }

            // The HoD's approval remark is the one printed on the report; a remark made earlier is used when there is none.
            Map<Long, String> approval = new HashMap<>();
            Map<Long, String> any = new HashMap<>();
            for (Object[] r : jdbc.sql("""
                    SELECT appraisal_id, action, comment FROM review_actions
                    WHERE actor_role = 'HOD' AND comment IS NOT NULL AND TRIM(comment) <> '' AND appraisal_id IN (:ids)
                    ORDER BY id""").param("ids", ids)
                    .query((rs, n) -> new Object[] {rs.getLong(1), rs.getString(2), rs.getString(3)}).list()) {
                long id = (Long) r[0];
                any.put(id, ((String) r[2]).strip());
                if ("HOD_APPROVE".equals(r[1])) approval.put(id, ((String) r[2]).strip());
            }
            for (Long id : ids) {
                String remark = approval.getOrDefault(id, any.get(id));
                if (remark != null) c.hodRemarks.put(id, remark);
            }
            return c;
        }

        private void add(JdbcClient jdbc, List<Long> ids, Map<Long, List<String>> into, String sql, Line line) {
            List<Object[]> found = jdbc.sql(sql).param("ids", ids).query((rs, n) -> new Object[] {rs.getLong("appraisal_id"), line.of(rs)}).list();
            for (Object[] f : found) into.computeIfAbsent((Long) f[0], k -> new ArrayList<>()).add((String) f[1]);
        }

        /** " (a; b; c)" from the parts that have something in them; nothing at all when none does. */
        private static String paren(String... parts) {
            List<String> kept = new ArrayList<>();
            for (String p : parts) {
                if (p != null && !p.isBlank()) kept.add(p.strip());
            }
            return kept.isEmpty() ? "" : " (" + String.join("; ", kept) + ")";
        }

        /** "01-06-2025 to 30-06-2025", one date alone, or nothing when neither is known. */
        private static String period(LocalDate from, LocalDate to) {
            if (from == null && to == null) return "";
            if (from == null || to == null || from.equals(to)) return ReportLabels.date(from == null ? to : from);
            return ReportLabels.date(from) + " to " + ReportLabels.date(to);
        }

        private static String opt(String prefix, String value) {
            return value == null || value.isBlank() ? "" : prefix + value.strip();
        }

        private static String feedback(java.math.BigDecimal one, java.math.BigDecimal two) {
            if (one == null && two == null) return "";
            return "; feedback " + (one == null ? NONE : ReportLabels.number(one)) + " / " + (two == null ? NONE : ReportLabels.number(two));
        }
    }
}
