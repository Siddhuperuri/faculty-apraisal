package edu.svec.fams.scoring;

import edu.svec.fams.scoring.ScoringRules.Line;
import edu.svec.fams.section.Sections;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * The score per criterion, worked out from the entries alone and recalculated whenever they are read, so it is always
 * what the entries are worth: nobody types a score. B1 earns the cadre's B1 maximum in eighths, one eighth for each
 * course handled (a faculty member teaches at most {@link Sections#MAX_COURSES} courses); B2 to B4 and B5 to B9 follow
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
        for (Stored s : stored) {
            if (s.criterion().equals("TEACHING_LEARNING")) teaching(s.maxMarks(), courses, components, breakdowns);
        }

        return stored.stream()
                .map(s -> {
                    String code = s.criterion();
                    boolean perEntry = Criteria.isPerEntry(code);
                    List<Line> lines = breakdowns.getOrDefault(code, List.of());
                    BigDecimal total = ScoringRules.total(lines).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
                    if (total.scale() < 0) total = total.setScale(0);
                    if (!perEntry) total = total.min(BigDecimal.valueOf(s.maxMarks()));
                    return new ScoreRow(code, Criteria.referenceOf(code), Criteria.labelOf(code), perEntry ? null : s.maxMarks(), total,
                            List.copyOf(components.getOrDefault(code, List.of())), List.copyOf(lines));
                })
                .sorted(Comparator.comparingInt(r -> Criteria.order(r.criterion())))
                .toList();
    }

    /**
     * B1: each course earns one eighth of the criterion's maximum, divided among its components as the policy divides
     * it (a policy that does not break the maximum down earns it as one line). Fills in each component's awarded marks.
     */
    private static void teaching(int maxMarks, int courses, Map<String, List<Component>> components, Map<String, List<Line>> breakdowns) {
        BigDecimal eighth = BigDecimal.valueOf(Sections.MAX_COURSES);
        List<Component> parts = components.get("TEACHING_LEARNING");
        List<Line> lines = new ArrayList<>();
        if (parts == null || parts.isEmpty()) {
            lines.add(ScoringRules.capped("Course handled (up to " + maxMarks + ")", BigDecimal.valueOf(maxMarks).divide(eighth, 4, RoundingMode.HALF_UP), courses, maxMarks));
        } else {
            for (int i = 0; i < parts.size(); i++) {
                Component c = parts.get(i);
                BigDecimal perCourse = BigDecimal.valueOf(c.maxMarks()).divide(eighth, 4, RoundingMode.HALF_UP);
                Line line = ScoringRules.capped(c.description() + " (up to " + c.maxMarks() + ")", perCourse, courses, c.maxMarks());
                lines.add(line);
                parts.set(i, new Component(c.description(), c.maxMarks(), line.marks().setScale(2, RoundingMode.HALF_UP).stripTrailingZeros()));
            }
        }
        breakdowns.put("TEACHING_LEARNING", lines);
    }
}
