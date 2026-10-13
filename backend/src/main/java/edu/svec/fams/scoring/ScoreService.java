package edu.svec.fams.scoring;

import edu.svec.fams.scoring.ScoringRules.Line;
import edu.svec.fams.section.Sections;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * The score per criterion, worked out from the entries alone and recalculated whenever they are read, so it is always
 * what the entries are worth: nobody types a score. B1 earns each of its components in eighths, one eighth for each
 * course handled (a faculty member teaches at most {@link Sections#MAX_COURSES} courses), the workload component being
 * rounded to a whole mark when fewer than 8 courses are entered; B2 to B4 and B5 to B9 follow
 * {@link ScoringRules}. B1 to B4 never exceed the cadre's maximum, which is the snapshot taken when the appraisal was
 * created (so a later policy change never alters it); B5 to B9 have no maximum.
 *
 * <p>This is also the one place the score sheet's criteria are read from: every screen and the printed report take
 * the cadre's criteria, maxima and scoring components from {@link #rows}.
 */
@Service
public class ScoreService {

    /**
     * One part of a criterion's maximum, in the college's wording (for example "Workload & course delivery", 20).
     * {@code awarded} is set where the application works out the marks of that part itself (B1's components).
     */
    public record Component(String description, int maxMarks, BigDecimal awarded) {
        /** A component whose marks are not worked out separately. */
        public Component(String description, int maxMarks) { this(description, maxMarks, null); }
    }

    /**
     * @param reference  the criterion's number in Part B ("B1")
     * @param maxMarks   null for B5 to B9, which are marked per entry with no upper limit
     * @param score      the marks that count, worked out from the entries
     * @param components empty where the maximum is not broken down
     * @param breakdown  the rate card with the faculty member's counts
     */
    public record ScoreRow(String criterion, String reference, String label, Integer maxMarks, BigDecimal score,
                           List<Component> components, List<Line> breakdown) {}

    private record Stored(String criterion, int maxMarks) {}

    private final JdbcClient jdbc;
    private final ScoringRules rules;

    public ScoreService(JdbcClient jdbc, ScoringRules rules) {
        this.jdbc = jdbc;
        this.rules = rules;
    }

    /**
     * The score sheet rows in the form's order, each with the scoring components of the appraisal's own policy version
     * (so of the faculty member's cadre, as it stood when the appraisal was started).
     */
    public List<ScoreRow> rows(long appraisalId) {
        Map<String, List<Component>> components = new HashMap<>();
        jdbc.sql("""
                SELECT pc.criterion, pc.description, pc.max_marks
                FROM appraisals a JOIN scoring_policy_components pc ON pc.policy_id = a.scoring_policy_id
                WHERE a.id = ? ORDER BY pc.criterion, pc.sort_order""")
                .param(appraisalId)
                .query(rs -> {
                    components.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(new Component(rs.getString(2), rs.getInt(3)));
                });
        Map<String, List<Line>> breakdowns = new HashMap<>(rules.lines(appraisalId));
        List<Stored> stored = jdbc.sql("SELECT criterion, max_marks FROM appraisal_scores WHERE appraisal_id = ?")
                .param(appraisalId).query((rs, n) -> new Stored(rs.getString("criterion"), rs.getInt("max_marks"))).list();

        int courses = jdbc.sql("SELECT COUNT(*) FROM teaching_courses WHERE appraisal_id = ?").param(appraisalId).query(Integer.class).single();
        int instructed = jdbc.sql("SELECT COUNT(*) FROM teaching_courses WHERE appraisal_id = ? AND course_role = 'INSTRUCTOR'")
                .param(appraisalId).query(Integer.class).single();
        Integer coursesPerYear = jdbc.sql("""
                SELECT p.courses_per_year FROM appraisals a JOIN scoring_policies p ON p.id = a.scoring_policy_id WHERE a.id = ?""")
                .param(appraisalId).query((rs, n) -> (Integer) rs.getObject(1)).optional().orElse(null);
        for (Stored s : stored) {
            if (s.criterion().equals("TEACHING_LEARNING")) {
                if (coursesPerYear != null) teachingByCadre(s.maxMarks(), courses, courses > 0 && instructed == courses, coursesPerYear, components, breakdowns);
                else teaching(s.maxMarks(), courses, components, breakdowns);
                breakdowns.get("TEACHING_LEARNING").addAll(roleLines(appraisalId));
            }
        }

        return stored.stream()
                .map(s -> {
                    String code = s.criterion();
                    boolean perEntry = Criteria.isPerEntry(code);
                    List<Line> lines = breakdowns.getOrDefault(code, List.of());
                    BigDecimal total = ScoringRules.total(lines).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
                    if (total.scale() < 0) total = total.setScale(0);
                    // B1 to B4 never exceed their maximum; B6, B8 and B9 stop at the limit the policy sets (when it sets one)
                    Integer limit = null;
                    if (!perEntry || (Criteria.isCappable(code) && s.maxMarks() > 0)) limit = s.maxMarks();
                    if (limit != null) total = total.min(BigDecimal.valueOf(limit));
                    return new ScoreRow(code, Criteria.referenceOf(code), Criteria.labelOf(code), limit, total,
                            List.copyOf(components.getOrDefault(code, List.of())), List.copyOf(lines));
                })
                .sorted(Comparator.comparingInt(r -> Criteria.order(r.criterion())))
                .toList();
    }

    /**
     * The institutional roles the faculty member holds (Head of the Department, Associate Dean ...), each earning the marks
     * the appraisal's scoring policy sets for that role. A role with no marks set earns nothing.
     */
    private List<Line> roleLines(long appraisalId) {
        return jdbc.sql("""
                SELECT COALESCE(rv.label, h.role_code), COALESCE(m.marks, 0)
                FROM institutional_roles_held h
                JOIN appraisals a ON a.id = h.appraisal_id
                LEFT JOIN scoring_policy_role_marks m ON m.policy_id = a.scoring_policy_id AND m.role_code = h.role_code
                LEFT JOIN reference_values rv ON rv.list_code = 'INSTITUTIONAL_ROLE' AND rv.code = h.role_code AND rv.parent_code = ''
                WHERE h.appraisal_id = ? ORDER BY h.id""")
                .param(appraisalId)
                .query((rs, n) -> {
                    BigDecimal marks = BigDecimal.valueOf(rs.getInt(2));
                    return new Line("Institutional role: " + rs.getString(1), marks, 1, marks);
                }).list();
    }

    /** The course-file / assessment-quality part is worth this much (at most) to someone who is only ever a course instructor. */
    static final int INSTRUCTOR_ONLY_COURSE_FILE_MAX = 4;

    /**
     * B1 under the cadre workload rule (policy version 5 onwards). The workload part earns its maximum divided by the
     * courses the cadre teaches in a year for each course (Lecturer, Assistant Professor and Senior Assistant Professor 8
     * courses, 2.5 marks each; Associate Professor 4, 5 marks; Professor 2, 10 marks), never more than the part's maximum, so
     * a faculty member is never made to enter more courses than the cadre needs. The course-file / assessment-quality part
     * is worth its whole maximum (10) when the faculty member coordinates at least one course they entered, and 4 when
     * every course entered is, explicitly, as a course instructor (a course saved before roles were asked for has no role and
     * is given the benefit of the doubt); it earns nothing until a course is entered. The other parts earn an
     * eighth of their maximum for each course, as before.
     */
    private static void teachingByCadre(int maxMarks, int courses, boolean instructorOnly, int coursesPerYear,
                                        Map<String, List<Component>> components, Map<String, List<Line>> breakdowns) {
        BigDecimal eighth = BigDecimal.valueOf(Sections.MAX_COURSES);
        List<Component> parts = components.get("TEACHING_LEARNING");
        List<Line> lines = new ArrayList<>();
        if (parts == null || parts.isEmpty()) {
            lines.add(ScoringRules.cappedMax("Course handled", BigDecimal.valueOf(maxMarks).divide(eighth, 4, RoundingMode.HALF_UP), courses, maxMarks));
        } else {
            for (int i = 0; i < parts.size(); i++) {
                Component c = parts.get(i);
                String name = c.description().toLowerCase(Locale.ROOT);
                Line line;
                int shownMax = c.maxMarks();
                if (name.startsWith("workload")) {
                    BigDecimal perCourse = BigDecimal.valueOf(c.maxMarks()).divide(BigDecimal.valueOf(coursesPerYear), 4, RoundingMode.HALF_UP);
                    line = ScoringRules.cappedMax(c.description(), perCourse, courses, c.maxMarks());
                } else if (name.contains("course-file")) {
                    shownMax = instructorOnly ? Math.min(INSTRUCTOR_ONLY_COURSE_FILE_MAX, c.maxMarks()) : c.maxMarks();
                    BigDecimal awarded = courses > 0 ? BigDecimal.valueOf(shownMax) : BigDecimal.ZERO;
                    line = new Line(c.description() + " (" + ScoringRules.maxMarks(shownMax) + ")", BigDecimal.valueOf(shownMax),
                            courses > 0 ? 1 : 0, awarded, shownMax);
                } else {
                    BigDecimal perCourse = BigDecimal.valueOf(c.maxMarks()).divide(eighth, 4, RoundingMode.HALF_UP);
                    line = ScoringRules.cappedMax(c.description(), perCourse, courses, c.maxMarks());
                }
                lines.add(line);
                parts.set(i, new Component(c.description(), shownMax, line.marks().setScale(2, RoundingMode.HALF_UP).stripTrailingZeros()));
            }
        }
        breakdowns.put("TEACHING_LEARNING", lines);
    }

    /**
     * B1: each course earns one eighth of each component's maximum, so 8 courses earn all of it (a policy that does not
     * break the maximum down earns it as one line). The workload component is rounded to the nearest whole mark (half up),
     * so no decimal workload marks are shown or counted: for a Professor, 15 marks make 1.875 a course, 3 courses are
     * 5.625 and earn 6, and 8 courses are exactly 15. Fills in each component's awarded marks.
     */
    private static void teaching(int maxMarks, int courses, Map<String, List<Component>> components, Map<String, List<Line>> breakdowns) {
        BigDecimal eighth = BigDecimal.valueOf(Sections.MAX_COURSES);
        List<Component> parts = components.get("TEACHING_LEARNING");
        List<Line> lines = new ArrayList<>();
        if (parts == null || parts.isEmpty()) {
            lines.add(ScoringRules.cappedMax("Course handled", BigDecimal.valueOf(maxMarks).divide(eighth, 4, RoundingMode.HALF_UP), courses, maxMarks));
        } else {
            for (int i = 0; i < parts.size(); i++) {
                Component c = parts.get(i);
                BigDecimal perCourse = BigDecimal.valueOf(c.maxMarks()).divide(eighth, 4, RoundingMode.HALF_UP);
                boolean workload = c.description().toLowerCase(Locale.ROOT).startsWith("workload");
                Line line;
                if (workload) {
                    // worked out from the exact figure (max x courses / 8), then rounded once, so there is no rounding drift
                    BigDecimal marks = BigDecimal.valueOf((long) c.maxMarks() * Math.min(courses, Sections.MAX_COURSES))
                            .divide(eighth, 0, RoundingMode.HALF_UP);
                    BigDecimal exact = BigDecimal.valueOf(c.maxMarks()).multiply(BigDecimal.valueOf(Math.min(courses, Sections.MAX_COURSES)))
                            .divide(eighth, 4, RoundingMode.HALF_UP).stripTrailingZeros();
                    String working = courses + " course" + (courses == 1 ? "" : "s") + " x " + c.maxMarks() + " / " + Sections.MAX_COURSES
                            + " = " + exact.toPlainString() + ", to a whole mark";
                    line = new Line(c.description() + " (" + working + "; " + ScoringRules.maxMarks(c.maxMarks()) + ")", perCourse, courses,
                            marks.min(BigDecimal.valueOf(c.maxMarks())), c.maxMarks());
                } else {
                    line = ScoringRules.cappedMax(c.description(), perCourse, courses, c.maxMarks());
                }
                lines.add(line);
                parts.set(i, new Component(c.description(), c.maxMarks(), line.marks().setScale(2, RoundingMode.HALF_UP).stripTrailingZeros()));
            }
        }
        breakdowns.put("TEACHING_LEARNING", lines);
    }
}
