package edu.svec.fams.console;

import edu.svec.fams.audit.AuditQueryService;
import edu.svec.fams.appraisal.AppraisalStatus;
import edu.svec.fams.auth.AccountStatus;
import edu.svec.fams.auth.FamsUserPrincipal;
import edu.svec.fams.auth.Role;
import edu.svec.fams.common.ApiException;
import edu.svec.fams.scoring.Criteria;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The overview screens for the Head of the Department, the Principal (and the Director Technical, who stands at the same
 * level and is shown the same thing) and the administrator. Everything here is
 * read-only and respects the same visibility rules as the appraisals themselves:
 * <ul>
 *   <li>an HoD sees only their departments, and a draft is shown as "not yet submitted" with no link, so a draft's
 *       existence is not revealed;</li>
 *   <li>the Principal sees individual appraisals only from the HoD's approval onward; everything earlier is a single
 *       aggregate number ("not yet with you");</li>
 *   <li>an administrator sees counts and set-up checks, never a name or any appraisal content.</li>
 * </ul>
 */
@Service
public class ConsoleService {

    /** Where an appraisal is, in the words of the Head of the Department: used to group the counts on that console. */
    public enum Stage { NOT_SUBMITTED, NEEDS_HOD, ONWARD, APPROVED }

    public record YearRef(long id, String name) {}

    // ---- HoD ----
    public record DepartmentSummary(long id, String code, String name, int faculty, Map<String, Integer> counts) {}
    /** @param queryRaised the HoD is reviewing it and has sent the faculty member a message, so it is waiting for them */
    public record RosterRow(Long appraisalId, String name, String employeeId, String department, String cadre,
                            String status, String stage, OffsetDateTime submittedAt, OffsetDateTime updatedAt,
                            boolean queryRaised) {}
    public record HodConsole(YearRef year, List<YearRef> years, List<DepartmentSummary> departments,
                             Map<String, Integer> totals, List<RosterRow> roster) {}

    // ---- Principal ----
    public record PrincipalDepartment(long id, String code, String name, int faculty, int awaiting, int approved, int notYetWithYou) {}
    public record Awaiting(long appraisalId, String name, String employeeId, String department, String status,
                           OffsetDateTime updatedAt) {}
    public record PrincipalTotals(int faculty, int awaiting, int approved, int notYetWithYou) {}
    public record PrincipalConsole(YearRef year, List<YearRef> years, List<PrincipalDepartment> departments, PrincipalTotals totals,
                                   List<Awaiting> awaitingList) {}

    // ---- Administrator ----
    public record RoleCount(String role, int active, int disabled) {}
    public record Check(String code, String level, String message, String link) {}
    public record AdminOverview(YearRef year, List<RoleCount> accounts, int neverSignedIn, Map<String, Integer> pipeline,
                                List<Check> checks, List<AuditQueryService.Entry> recent) {}

    /** A department's roster; far above any real department. */
    static final int ROSTER_LIMIT = 500;
    /** The whole college, for the Principal's totals: a sanity bound, not a page size. */
    static final int COLLEGE_LIMIT = 5000;
    static final int AWAITING_LIMIT = 10;
    static final int RECENT_ACTIVITY = 8;

    private final JdbcClient jdbc;
    private final AuditQueryService audit;

    public ConsoleService(JdbcClient jdbc, AuditQueryService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ shared

    private List<YearRef> years() {
        return jdbc.sql("SELECT id, name FROM academic_years ORDER BY start_date DESC, id DESC")
                .query((rs, n) -> new YearRef(rs.getLong(1), rs.getString(2))).list();
    }

    /** The year asked for, else the open year that begins latest (the one new appraisals start in), else the latest year. */
    private YearRef pickYear(List<YearRef> all, Long requested) {
        if (requested != null) {
            return all.stream().filter(y -> y.id() == requested).findFirst().orElseThrow(ApiException::notFound);
        }
        Long open = jdbc.sql("SELECT id FROM academic_years WHERE active = TRUE ORDER BY start_date DESC, id DESC LIMIT 1")
                .query(Long.class).optional().orElse(null);
        if (open != null) return all.stream().filter(y -> y.id() == open).findFirst().orElse(null);
        return all.isEmpty() ? null : all.get(0);
    }

    static Stage stage(String status) {
        if (status == null) return Stage.NOT_SUBMITTED;
        return switch (AppraisalStatus.valueOf(status)) {
            case DRAFT -> Stage.NOT_SUBMITTED;
            case SUBMITTED, HOD_REVIEW -> Stage.NEEDS_HOD;
            case HOD_APPROVED, PRINCIPAL_REVIEW -> Stage.ONWARD;      // forwarded by the HoD and with the Principal or Director Technical
            case APPROVED -> Stage.APPROVED;
        };
    }

    private static Map<String, Integer> emptyCounts() {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (Stage s : Stage.values()) m.put(s.name(), 0);
        return m;
    }

    /** One row per faculty member of the department(s), with this year's appraisal if there is one. */
    private record Person(long departmentId, String departmentCode, String departmentName, String name, String employeeId,
                          String cadre, Long appraisalId, String status, OffsetDateTime submittedAt, OffsetDateTime updatedAt,
                          boolean queryRaised) {}

    private List<Person> people(Long yearId, String departmentFilterSql, List<Object> filterParams, int limit) {
        List<Object> params = new ArrayList<>();
        params.add(yearId);
        params.addAll(filterParams);
        return jdbc.sql("""
                SELECT d.id, d.code, d.name AS dept, fp.name, fp.employee_id, c.name AS cadre, a.id AS aid, a.status,
                       a.submitted_at, a.updated_at,
                       (a.status = 'HOD_REVIEW' AND EXISTS (SELECT 1 FROM appraisal_messages m WHERE m.appraisal_id = a.id AND m.answered_at IS NULL)) AS query_raised
                FROM faculty_profiles fp
                JOIN users u ON u.id = fp.user_id
                JOIN departments d ON d.id = fp.department_id
                JOIN cadres c ON c.id = fp.cadre_id
                LEFT JOIN appraisals a ON a.faculty_id = fp.id AND a.academic_year_id = ?
                WHERE (u.status = 'ACTIVE' OR (a.id IS NOT NULL AND a.status <> 'DRAFT'))
                """ + departmentFilterSql + " ORDER BY d.code, fp.name LIMIT " + limit)
                .params(params)
                .query((rs, n) -> new Person(rs.getLong("id"), rs.getString("code"), rs.getString("dept"), rs.getString("name"),
                        rs.getString("employee_id"), rs.getString("cadre"), rs.getObject("aid", Long.class), rs.getString("status"),
                        rs.getObject("submitted_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class),
                        rs.getBoolean("query_raised")))
                .list();
    }

    // ------------------------------------------------------------------ Head of the Department

    @Transactional(readOnly = true)
    public HodConsole hod(FamsUserPrincipal user, Long requestedYear) {
        List<YearRef> years = years();
        YearRef year = pickYear(years, requestedYear);

        List<Person> people = people(year == null ? null : year.id(),
                " AND fp.department_id IN (SELECT department_id FROM hod_assignments WHERE user_id = ?)", List.of(user.id()), ROSTER_LIMIT);

        List<DepartmentSummary> departments = new ArrayList<>();
        Map<String, Integer> totals = emptyCounts();
        for (var d : jdbc.sql("SELECT d.id, d.code, d.name FROM hod_assignments h JOIN departments d ON d.id = h.department_id "
                        + "WHERE h.user_id = ? ORDER BY d.code").param(user.id())
                .query((rs, n) -> new Object[] {rs.getLong(1), rs.getString(2), rs.getString(3)}).list()) {
            Map<String, Integer> counts = emptyCounts();
            int faculty = 0;
            for (Person p : people) {
                if (p.departmentId() != (Long) d[0]) continue;
                faculty++;
                counts.merge(stage(p.status()).name(), 1, Integer::sum);
            }
            counts.forEach((k, v) -> totals.merge(k, v, Integer::sum));
            departments.add(new DepartmentSummary((Long) d[0], (String) d[1], (String) d[2], faculty, counts));
        }

        // A draft is private to its author: it is listed as "not yet submitted", with no appraisal to open.
        List<RosterRow> roster = people.stream().map(p -> {
            boolean visible = p.status() != null && !p.status().equals(AppraisalStatus.DRAFT.name());
            return new RosterRow(visible ? p.appraisalId() : null, p.name(), p.employeeId(), p.departmentCode(), p.cadre(),
                    visible ? p.status() : null, stage(p.status()).name(), visible ? p.submittedAt() : null,
                    visible ? p.updatedAt() : null, visible && p.queryRaised());
        }).sorted(Comparator
                .comparing((RosterRow r) -> !r.stage().equals(Stage.NEEDS_HOD.name()))
                .thenComparing(RosterRow::department).thenComparing(RosterRow::name)).toList();

        return new HodConsole(year, years, departments, totals, roster);
    }

    // ------------------------------------------------------------------ Principal

    private static final Set<AppraisalStatus> AWAITING_PRINCIPAL = EnumSet.of(AppraisalStatus.HOD_APPROVED, AppraisalStatus.PRINCIPAL_REVIEW);

    @Transactional(readOnly = true)
    public PrincipalConsole principal(Long requestedYear) {
        List<YearRef> years = years();
        YearRef year = pickYear(years, requestedYear);
        List<Person> people = people(year == null ? null : year.id(), "", List.of(), COLLEGE_LIMIT);

        List<PrincipalDepartment> departments = new ArrayList<>();
        int[] sum = new int[4];
        for (var d : jdbc.sql("SELECT id, code, name FROM departments WHERE active = TRUE "
                        + "OR EXISTS (SELECT 1 FROM faculty_profiles fp WHERE fp.department_id = departments.id) ORDER BY code")
                .query((rs, n) -> new Object[] {rs.getLong(1), rs.getString(2), rs.getString(3)}).list()) {
            int faculty = 0, awaiting = 0, approved = 0;
            for (Person p : people) {
                if (p.departmentId() != (Long) d[0]) continue;
                faculty++;
                AppraisalStatus s = p.status() == null ? null : AppraisalStatus.valueOf(p.status());
                if (s == AppraisalStatus.APPROVED) {
                    approved++;
                } else if (s != null && AWAITING_PRINCIPAL.contains(s)) {
                    awaiting++;
                }
            }
            int notYet = faculty - awaiting - approved;     // not submitted, or still with the Head of the Department
            departments.add(new PrincipalDepartment((Long) d[0], (String) d[1], (String) d[2], faculty, awaiting, approved, notYet));
            sum[0] += faculty; sum[1] += awaiting; sum[2] += approved; sum[3] += notYet;
        }

        List<Awaiting> waiting = people.stream()
                .filter(p -> p.status() != null && AWAITING_PRINCIPAL.contains(AppraisalStatus.valueOf(p.status())))
                .sorted(Comparator.comparing(Person::updatedAt))        // oldest first: nothing should languish
                .map(p -> new Awaiting(p.appraisalId(), p.name(), p.employeeId(), p.departmentCode(), p.status(), p.updatedAt()))
                .limit(AWAITING_LIMIT).toList();

        return new PrincipalConsole(year, years, departments, new PrincipalTotals(sum[0], sum[1], sum[2], sum[3]), waiting);
    }

    // ------------------------------------------------------------------ Administrator

    @Transactional(readOnly = true)
    public AdminOverview admin() {
        List<YearRef> years = years();
        YearRef year = pickYear(years, null);

        Map<String, int[]> byRole = new LinkedHashMap<>();
        for (Role r : Role.values()) byRole.put(r.name(), new int[2]);
        jdbc.sql("SELECT role, status, COUNT(*) FROM users GROUP BY role, status").query((rs, n) -> {
            int[] c = byRole.get(rs.getString(1));
            if (c != null) c[AccountStatus.isActive(rs.getString(2)) ? 0 : 1] += rs.getInt(3);
            return null;
        }).list();
        List<RoleCount> accounts = byRole.entrySet().stream().map(e -> new RoleCount(e.getKey(), e.getValue()[0], e.getValue()[1])).toList();
        int neverSignedIn = jdbc.sql("SELECT COUNT(*) FROM users WHERE status = 'ACTIVE' AND last_login_at IS NULL")
                .query(Integer.class).single();

        // Counts by stage only: no names, no content. "NOT_STARTED" is active faculty with no appraisal in the year.
        Map<String, Integer> pipeline = new LinkedHashMap<>();
        pipeline.put("NOT_STARTED", 0);
        for (AppraisalStatus s : AppraisalStatus.values()) pipeline.put(s.name(), 0);
        if (year != null) {
            int[] started = {0};
            jdbc.sql("""
                    SELECT a.status, COUNT(*) FROM appraisals a
                    JOIN faculty_profiles fp ON fp.id = a.faculty_id JOIN users u ON u.id = fp.user_id
                    WHERE a.academic_year_id = ? AND (u.status = 'ACTIVE' OR a.status <> 'DRAFT') GROUP BY a.status""")
                    .param(year.id()).query((rs, n) -> {
                        pipeline.put(rs.getString(1), rs.getInt(2));
                        started[0] += rs.getInt(2);
                        return null;
                    }).list();
            int facultyActive = byRole.get("FACULTY")[0];
            pipeline.put("NOT_STARTED", Math.max(0, facultyActive - started[0]));
        }

        return new AdminOverview(year, accounts, neverSignedIn, pipeline, checks(year, byRole),
                audit.query(0, RECENT_ACTIVITY, null, null, null).items());
    }

    private List<Check> checks(YearRef openYear, Map<String, int[]> byRole) {
        List<Check> out = new ArrayList<>();

        boolean open = jdbc.sql("SELECT COUNT(*) FROM academic_years WHERE active = TRUE").query(Integer.class).single() > 0;
        out.add(open
                ? new Check("OPEN_YEAR", "ok", "An academic year is open for appraisals (" + openYear.name() + ").", null)
                : new Check("OPEN_YEAR", "problem", "No academic year is open, so faculty cannot start an appraisal.", "/admin/setup"));

        if (open) {
            List<String> missing = new ArrayList<>();
            for (var c : jdbc.sql("SELECT id, name FROM cadres WHERE active = TRUE ORDER BY id")
                    .query((rs, n) -> new Object[] {rs.getLong(1), rs.getString(2)}).list()) {
                Integer criteria = jdbc.sql("""
                        SELECT COUNT(*) FROM scoring_policies p JOIN scoring_policy_criteria k ON k.policy_id = p.id
                        WHERE p.academic_year_id = ? AND p.cadre_id = ? AND p.active = TRUE
                          AND p.version = (SELECT MAX(p2.version) FROM scoring_policies p2
                                           WHERE p2.academic_year_id = p.academic_year_id AND p2.cadre_id = p.cadre_id AND p2.active = TRUE)""")
                        .params(openYear.id(), c[0]).query(Integer.class).optional().orElse(null);
                if (criteria == null || criteria != Criteria.values().length) missing.add((String) c[1]);
            }
            out.add(missing.isEmpty()
                    ? new Check("POLICIES", "ok", "Every cadre has a scoring policy for " + openYear.name() + ".", null)
                    : new Check("POLICIES", "problem", "No complete scoring policy for " + String.join(", ", missing) + " in " + openYear.name()
                            + ": those faculty cannot start an appraisal.", "/admin/setup"));
        }

        out.add(hodCoverage());
        // The Principal and the Director Technical stand at the same level: either one can finalise an appraisal.
        int deciders = byRole.get("PRINCIPAL")[0] + byRole.get("DIRECTOR")[0];
        out.add(deciders > 0
                ? new Check("PRINCIPAL", "ok", "There is an active Principal or Director Technical account.", null)
                : new Check("PRINCIPAL", "problem",
                        "There is no active Principal or Director Technical account: appraisals forwarded by a Head of the Department cannot be finalised.",
                        "/admin/users"));

        int noRecord = jdbc.sql("""
                SELECT COUNT(*) FROM users u LEFT JOIN faculty_profiles fp ON fp.user_id = u.id
                WHERE u.role = 'FACULTY' AND u.status = 'ACTIVE' AND fp.id IS NULL""").query(Integer.class).single();
        out.add(noRecord == 0
                ? new Check("FACULTY_RECORDS", "ok", "Every faculty account has a faculty record.", null)
                : new Check("FACULTY_RECORDS", "problem", noRecord + " active faculty account" + (noRecord == 1 ? " has" : "s have")
                        + " no faculty record and cannot start an appraisal.", "/admin/users"));

        out.add(byRole.get("ADMIN")[0] >= 2
                ? new Check("ADMINS", "ok", "There are " + byRole.get("ADMIN")[0] + " active administrators.", null)
                : new Check("ADMINS", "warn", "Only one administrator can sign in. Add a second so the system can still be managed if that account is lost.", "/admin/users"));

        return out;
    }

    /** Every open department with active faculty needs an active Head of the Department. */
    private Check hodCoverage() {
        List<String> missing = jdbc.sql("""
                SELECT d.code FROM departments d
                WHERE d.active = TRUE
                  AND EXISTS (SELECT 1 FROM faculty_profiles fp JOIN users u ON u.id = fp.user_id AND u.status = 'ACTIVE' WHERE fp.department_id = d.id)
                  AND NOT EXISTS (SELECT 1 FROM hod_assignments a JOIN users ua ON ua.id = a.user_id AND ua.status = 'ACTIVE' WHERE a.department_id = d.id)
                ORDER BY d.code""").query(String.class).list();
        return missing.isEmpty()
                ? new Check("HOD_COVERAGE", "ok", "Every department with faculty has an active Head of the Department.", null)
                : new Check("HOD_COVERAGE", "problem", "No active Head of the Department for " + String.join(", ", missing)
                        + ": submitted appraisals there cannot be reviewed.", "/admin/users");
    }
}
