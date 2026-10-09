package edu.svec.fams.scoring;

import edu.svec.fams.appraisal.AppraisalAccess;
import edu.svec.fams.audit.AuditService;
import edu.svec.fams.auth.FamsUserPrincipal;
import edu.svec.fams.common.ValidationException;
import edu.svec.fams.scoring.ScoringRules.Line;
import edu.svec.fams.section.FieldSpec;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The score per criterion. B5 to B9 are marked per entry ({@link ScoringRules}) and B1's workload is 2.5 marks a course;
 * those marks are calculated from what the faculty member has entered, live. They may overwrite the calculation with a
 * score of their own (the self-score; clearing it returns to the calculation). B2 to B4 and the rest of B1 are typed in.
 * A typed score is checked only against the rules the form states: not below 0, and for B1 to B4 not above the cadre
 * maximum (max_marks is the snapshot taken when the appraisal was created, so a later policy change never alters it).
 * B5 to B9 have no maximum.
 *
 * <p>This is also the one place the score sheet's criteria are read from: every screen and the printed report take
 * the cadre's criteria, maxima and scoring components from {@link #rows}.
 */
@Service
public class ScoreService {

    /**
     * One part of a criterion's maximum, in the college's wording (for example "Workload & course delivery", 20).
     * {@code awarded} is set only where the application awards the marks itself (the workload component).
     */
    public record Component(String description, int maxMarks, BigDecimal awarded) {
        /** A component whose marks the faculty member scores. */
        public Component(String description, int maxMarks) { this(description, maxMarks, null); }
    }

    /** Marks awarded automatically for each course handled, to the first component of Teaching & Learning. */
    public static final BigDecimal MARKS_PER_COURSE = new BigDecimal("2.5");
    /**
     * Every faculty member must add at least this many courses before submitting (the college's rule): 4 in each of the
     * academic year's 2 semesters. Eight courses at {@link #MARKS_PER_COURSE} earn the workload component's 20 marks.
     */
    public static final int MIN_COURSES = 8;
    /** A typed score for a per-entry criterion is only checked to be sensible, not against a maximum. */
    private static final int MAX_PER_ENTRY_SCORE = 100000;

    /**
     * @param reference  the criterion's number in Part B ("B1")
     * @param maxMarks   null for B5 to B9, which are marked per entry with no upper limit
     * @param selfScore  what the faculty member typed over the calculated marks; null while they accept the calculation
     * @param calculated the marks worked out from the entries (B1's workload and B5 to B9), null where nothing is calculated
     * @param score      the marks that count: {@code selfScore} if given, otherwise {@code calculated}; null if neither
     * @param components empty where the maximum is not broken down
     * @param breakdown  the rate card with the faculty member's counts, empty where nothing is calculated
     */
    public record ScoreRow(String criterion, String reference, String label, Integer maxMarks, BigDecimal selfScore,
                           BigDecimal calculated, BigDecimal score, List<Component> components, List<Line> breakdown) {}

    private final JdbcClient jdbc;
    private final AppraisalAccess access;
    private final AuditService audit;
    private final ScoringRules rules;

    public ScoreService(JdbcClient jdbc, AppraisalAccess access, AuditService audit, ScoringRules rules) {
        this.rules = rules;
        this.jdbc = jdbc;
        this.access = access;
        this.audit = audit;
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
        Map<String, BigDecimal> calculated = new HashMap<>();
        breakdowns.forEach((k, v) -> calculated.put(k, ScoringRules.total(v)));
        List<Component> teaching = components.get("TEACHING_LEARNING");
        if (teaching != null && !teaching.isEmpty()) {
            int courses = jdbc.sql("SELECT COUNT(*) FROM teaching_courses WHERE appraisal_id = ?").param(appraisalId).query(Integer.class).single();
            Component first = teaching.get(0);
            BigDecimal awarded = MARKS_PER_COURSE.multiply(BigDecimal.valueOf(courses)).min(BigDecimal.valueOf(first.maxMarks()));
            teaching.set(0, new Component(first.description(), first.maxMarks(), awarded));
            breakdowns.put("TEACHING_LEARNING", List.of(new Line(first.description() + " (up to " + first.maxMarks() + ")", MARKS_PER_COURSE, courses, awarded)));
            calculated.put("TEACHING_LEARNING", awarded);
        }
        return jdbc.sql("SELECT criterion, max_marks, self_score FROM appraisal_scores WHERE appraisal_id = ?")
                .param(appraisalId)
                .query((rs, n) -> {
                    String code = rs.getString("criterion");
                    BigDecimal self = rs.getBigDecimal("self_score");
                    BigDecimal calc = calculated.get(code);
                    return new ScoreRow(code, Criteria.referenceOf(code), Criteria.labelOf(code),
                            Criteria.isPerEntry(code) ? null : rs.getInt("max_marks"), self, calc, self != null ? self : calc,
                            List.copyOf(components.getOrDefault(code, List.of())), List.copyOf(breakdowns.getOrDefault(code, List.of())));
                })
                .list().stream()
                .sorted(Comparator.comparingInt(r -> Criteria.order(r.criterion())))
                .toList();
    }

    /**
     * Saves the scores in {@code incoming} (criterion code to number, or null to clear). Criteria that are not
     * mentioned are left alone. Everything is validated first; one bad value saves nothing.
     */
    @Transactional
    public List<ScoreRow> save(long appraisalId, FamsUserPrincipal user, Map<String, Object> incoming) {
        access.loadEditable(appraisalId, user); // locks the appraisal until commit; 403/404/409 as for sections
        if (incoming == null) throw new ValidationException(Map.of("scores", "scores is required."));

        Map<String, ScoreRow> current = new LinkedHashMap<>();
        for (ScoreRow r : rows(appraisalId)) current.put(r.criterion(), r);

        Map<String, String> errors = new LinkedHashMap<>();
        Map<String, BigDecimal> updates = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : incoming.entrySet()) {
            String code = e.getKey();
            String path = "scores." + code;
            ScoreRow row = current.get(code);
            if (row == null) {
                errors.put(path, "Not a recognised criterion.");
                continue;
            }
            String label = "Self-score for " + row.label();
            FieldSpec spec = FieldSpec.decimal("selfScore", "self_score", label, 0, row.maxMarks() == null ? MAX_PER_ENTRY_SCORE : row.maxMarks(), 2, false);
            Object value = spec.normalize(e.getValue(), path, errors);
            if (row.maxMarks() != null && row.maxMarks() == 0 && errors.containsKey(path) && errors.get(path).contains("must be between")) {
                errors.put(path, label + " is not applicable for your cadre (maximum 0).");
            }
            if (!errors.containsKey(path)) updates.put(code, (BigDecimal) value);
        }
        if (!errors.isEmpty()) throw new ValidationException(errors);

        int changes = 0;
        for (Map.Entry<String, BigDecimal> u : updates.entrySet()) {
            BigDecimal before = current.get(u.getKey()).selfScore();
            BigDecimal after = u.getValue();
            boolean same = before == null ? after == null : after != null && before.compareTo(after) == 0;
            if (same) continue;
            jdbc.sql("UPDATE appraisal_scores SET self_score = ? WHERE appraisal_id = ? AND criterion = ?")
                    .params(after, appraisalId, u.getKey()).update();
            changes++;
        }
        if (changes > 0) {
            jdbc.sql("UPDATE appraisals SET updated_at = CURRENT_TIMESTAMP WHERE id = ?").param(appraisalId).update();
            audit.record(user.id(), "SCORES_SAVED", "APPRAISAL", appraisalId, "{\"changes\":" + changes + "}");
        }
        return rows(appraisalId);
    }
}
