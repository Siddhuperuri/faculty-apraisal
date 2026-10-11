package edu.svec.fams.admin;

import edu.svec.fams.common.ApiException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Aggregated figures for the administrator's dashboard: appraisal progress by department and by status, user counts and
 * the submission and approval rates, for one academic year. Only counts leave this class: no name, e-mail address,
 * employee ID or any appraisal content, so the dashboard shows the administrator how far the year has got without
 * letting them read anyone's appraisal.
 */
@Service
public class AdminAnalyticsService {

    public record YearRef(long id, String name, boolean active) {}

    /** One department: its faculty, and its appraisals by status (a status with none is 0), never who they belong to. */
    public record DepartmentFigures(long id, String code, String name, boolean active, int faculty, int headsOfDepartment,
                                    int started, int submitted, int approved, Map<String, Integer> byStatus,
                                    double submissionRate, double approvalRate) {}

    public record RoleCount(String role, int active, int disabled) {}

    public record Totals(int faculty, int started, int notStarted, int submitted, int approved, double submissionRate,
                         double approvalRate) {}

    public record Analytics(YearRef year, List<YearRef> years, Totals totals, Map<String, Integer> byStatus,
                            List<DepartmentFigures> departments, List<RoleCount> users, int neverSignedIn) {}

    /** The workflow statuses in the order of the chain. */
    static final List<String> STATUSES = List.of("DRAFT", "SUBMITTED", "HOD_REVIEW", "HOD_APPROVED", "PRINCIPAL_REVIEW", "APPROVED");

    private final JdbcClient jdbc;

    public AdminAnalyticsService(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Transactional(readOnly = true)
    public Analytics analytics(Long yearId) {
        List<YearRef> years = jdbc.sql("SELECT id, name, active FROM academic_years ORDER BY start_date DESC, id DESC")
                .query((rs, n) -> new YearRef(rs.getLong(1), rs.getString(2), rs.getBoolean(3))).list();
        if (years.isEmpty()) throw ApiException.notFound();
        YearRef year = yearId == null
                ? years.stream().filter(YearRef::active).findFirst().orElse(years.get(0))
                : years.stream().filter(y -> y.id() == yearId).findFirst().orElseThrow(ApiException::notFound);

        Map<Long, Map<String, Integer>> byDept = new LinkedHashMap<>();
        Map<String, Integer> overall = zeroes();
        jdbc.sql("""
                SELECT fp.department_id, a.status, COUNT(*) FROM appraisals a
                JOIN faculty_profiles fp ON fp.id = a.faculty_id
                WHERE a.academic_year_id = ? GROUP BY fp.department_id, a.status""").param(year.id())
                .query(rs -> {
                    byDept.computeIfAbsent(rs.getLong(1), k -> zeroes()).merge(rs.getString(2), rs.getInt(3), Integer::sum);
                    overall.merge(rs.getString(2), rs.getInt(3), Integer::sum);
                });

        Map<Long, Integer> faculty = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT fp.department_id, COUNT(*) FROM faculty_profiles fp JOIN users u ON u.id = fp.user_id
                WHERE u.status = 'ACTIVE' AND u.role = 'FACULTY' GROUP BY fp.department_id""")
                .query(rs -> { faculty.put(rs.getLong(1), rs.getInt(2)); });
        Map<Long, Integer> heads = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT h.department_id, COUNT(DISTINCT h.user_id) FROM hod_assignments h JOIN users u ON u.id = h.user_id
                WHERE u.status = 'ACTIVE' GROUP BY h.department_id""")
                .query(rs -> { heads.put(rs.getLong(1), rs.getInt(2)); });

        List<DepartmentFigures> departments = new ArrayList<>();
        int totalFaculty = 0;
        for (var d : jdbc.sql("SELECT id, code, name, active FROM departments ORDER BY code")
                .query((rs, n) -> new Object[] {rs.getLong(1), rs.getString(2), rs.getString(3), rs.getBoolean(4)}).list()) {
            long id = (Long) d[0];
            Map<String, Integer> by = byDept.getOrDefault(id, zeroes());
            int f = faculty.getOrDefault(id, 0);
            boolean active = (Boolean) d[3];
            if (!active && f == 0 && by.values().stream().mapToInt(Integer::intValue).sum() == 0) continue;   // a closed one with nothing in it
            totalFaculty += f;
            int started = by.values().stream().mapToInt(Integer::intValue).sum();
            int submitted = started - by.get("DRAFT");
            int approved = by.get("APPROVED");
            departments.add(new DepartmentFigures(id, (String) d[1], (String) d[2], active, f, heads.getOrDefault(id, 0), started,
                    submitted, approved, by, rate(submitted, f), rate(approved, submitted)));
        }

        int started = overall.values().stream().mapToInt(Integer::intValue).sum();
        int submitted = started - overall.get("DRAFT");
        int approved = overall.get("APPROVED");
        Totals totals = new Totals(totalFaculty, started, Math.max(0, totalFaculty - started), submitted, approved,
                rate(submitted, totalFaculty), rate(approved, submitted));

        List<RoleCount> users = jdbc.sql("""
                SELECT role, SUM(status = 'ACTIVE'), SUM(status = 'DISABLED') FROM users GROUP BY role ORDER BY role""")
                .query((rs, n) -> new RoleCount(rs.getString(1), rs.getInt(2), rs.getInt(3))).list();
        int never = jdbc.sql("SELECT COUNT(*) FROM users WHERE status = 'ACTIVE' AND last_login_at IS NULL")
                .query(Integer.class).single();
        return new Analytics(year, years, totals, overall, departments, users, never);
    }

    private static Map<String, Integer> zeroes() {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (String s : STATUSES) m.put(s, 0);
        return m;
    }

    /** A share of 0 to 100 to one decimal place; 0 when there is nothing to divide by. */
    private static double rate(int part, int whole) {
        return whole <= 0 ? 0 : Math.round(1000.0 * part / whole) / 10.0;
    }
}
