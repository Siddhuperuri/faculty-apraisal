package edu.svec.fams.scoring;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * How entries become marks. Every mark on the score sheet is worked out here and in {@link ScoreService} from what the
 * faculty member has entered; nobody types a score. Only what was achieved, done or received in the academic year being
 * appraised counts (1 June to 31 May): an entry dated outside it earns nothing, whatever the form let in.
 *
 * <ul>
 *   <li>B1 Teaching &amp; Learning: each course handled earns the cadre's B1 maximum divided by the 8 courses a faculty
 *       member teaches in a year (so 8 courses earn the whole maximum), shared among the criterion's components as the
 *       policy divides it. Worked out by {@link ScoreService}, which knows the policy.</li>
 *   <li>B2 to B4: marks for each kind of entry, up to a cap for each; the caps add up to the criterion's maximum of 15.
 *       Mentoring is 6 marks when any student is mentored, project guidance 2 for each project up to 4, a workshop or
 *       FDP of at least 5 days earns 5 once and a certification 10 once. The rest are a working rule that is easy to
 *       change here.</li>
 *   <li>B5 to B9: the college's fixed rate card per entry, the same for every cadre, with no upper limit. A journal
 *       paper counts by its indexing (UGC-CARE and others earn nothing), Ph.D. scholars awarded are "guided" and
 *       registered or submitted are "guiding", M.Tech and MBA scholars awarded are "PG guided", and each UG project is
 *       "B.Tech guided".</li>
 * </ul>
 */
@Component
public class ScoringRules {

    /**
     * One line of a rate card with what the faculty member has: {@code marks = perEntry * count}, or {@code cap} when
     * that would be more.
     */
    public record Line(String description, BigDecimal perEntry, int count, BigDecimal marks, Integer cap) {
        public Line(String description, BigDecimal perEntry, int count, BigDecimal marks) {
            this(description, perEntry, count, marks, null);
        }
    }

    /** A workshop, FDP, seminar or training program counts towards the marks only when it lasted at least this many days. */
    static final int MIN_QUALIFYING_DAYS = 5;

    /** The first and last day of the appraisal's academic year; each use needs the appraisal id bound to its {@code ?}. */
    private static final String START = "(SELECT ay.start_date FROM appraisals ap JOIN academic_years ay ON ay.id = ap.academic_year_id WHERE ap.id = ?)";
    private static final String END = "(SELECT ay.end_date FROM appraisals ap JOIN academic_years ay ON ay.id = ap.academic_year_id WHERE ap.id = ?)";

    private final JdbcClient jdbc;

    public ScoringRules(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The lines of every criterion worked out from entries alone (B2 to B9), keyed by criterion code. */
    public Map<String, List<Line>> lines(long id) {
        Map<String, List<Line>> out = new LinkedHashMap<>();

        int mentees = jdbc.sql("SELECT COALESCE(SUM(total_students_mentored), 0) FROM student_mentoring WHERE appraisal_id = ?")
                .param(id).query(Integer.class).single();
        out.put("STUDENT_MENTORING", List.of(
                // 6 marks when any student is mentored, 0 when none: the number of students does not matter
                capped("Students mentored (6 marks if you mentored any students)", BigDecimal.valueOf(6), mentees > 0 ? 1 : 0, 6),
                capped("Student project guided (up to 4)", BigDecimal.valueOf(2), count("student_projects", null, id), 4),
                capped("Student achievement (up to 5)", BigDecimal.ONE, count("student_achievements", null, id, "month:month_year"), 5)));

        // One qualifying program earns all 5 marks and one certification all 10; further ones add nothing (the cap).
        out.put("FDP_CERTIFICATIONS", List.of(
                capped("Workshop, FDP, seminar or training of " + MIN_QUALIFYING_DAYS + " days or more (5 marks)", BigDecimal.valueOf(5),
                        count("fdps", "days >= " + MIN_QUALIFYING_DAYS, id), 5),
                capped("Certification (10 marks)", BigDecimal.TEN, count("certifications", "duration_weeks >= 1", id), 10)));

        out.put("ADMINISTRATIVE", List.of(
                capped("Department-level role (up to 4)", BigDecimal.valueOf(2), count("administrative_roles", "scope = 'DEPARTMENT'", id, "date:from_date", "date:to_date"), 4),
                capped("Institute-level role (up to 3)", new BigDecimal("1.5"), count("administrative_roles", "scope = 'INSTITUTE'", id, "date:from_date", "date:to_date"), 3),
                capped("Event organised or coordinated (up to 8)", BigDecimal.ONE, count("events", null, id, "date:start_date", "date:end_date"), 8)));

        List<Line> research = new ArrayList<>();
        research.add(line("SCI/SCIE journal paper", 15, count("journal_publications", "indexing = 'SCI_SCIE'", id, "month:month_year")));
        research.add(line("ESCI/Scopus journal paper", 10, count("journal_publications", "indexing = 'SCOPUS'", id, "month:month_year")));
        research.add(line("Conference paper published", 5, count("conference_papers", null, id, "month:month_year")));
        research.add(line("Ph.D. guided (awarded)", 10, count("research_scholars", "degree = 'PHD' AND status = 'AWARDED'", id, "year:year")));
        research.add(line("Ph.D. guiding (registered or submitted)", 5,
                count("research_scholars", "degree = 'PHD' AND status IN ('REGISTERED', 'SUBMITTED')", id, "year:year")));
        research.add(line("PG guided (M.Tech/MBA awarded)", 3,
                count("research_scholars", "degree IN ('MTECH', 'MBA') AND status = 'AWARDED'", id, "year:year")));
        research.add(line("B.Tech project guided", 2, count("student_projects", "level = 'UG'", id)));
        out.put("RESEARCH_PUBLICATIONS", research);

        out.put("FUNDED_PROJECTS", List.of(
                line("Project sanctioned", 50, count("funded_projects", "status = 'SANCTIONED'", id, "year:year")),
                line("Project applied for (not yet sanctioned)", 5, count("funded_projects", "status = 'APPLIED'", id, "year:year"))));

        out.put("PATENTS_BOOKS_IPR", List.of(
                line("Patent published", 5, count("patents_ipr", "status = 'PUBLISHED'", id, "date:record_date")),
                line("Patent granted", 20, count("patents_ipr", "status = 'GRANTED'", id, "date:record_date")),
                line("Book published", 20, count("books", "type = 'BOOK'", id, "month:month_year")),
                line("Book chapter published", 5, count("books", "type = 'CHAPTER'", id, "month:month_year"))));

        out.put("OUTREACH", List.of(line("Outreach entry", 5, count("outreach", null, id, "date:event_date"))));
        out.put("MEMBERSHIPS_AWARDS", List.of(line("Membership, award or recognition", 5, count("memberships_awards", null, id, "year:year"))));
        return out;
    }

    public static BigDecimal total(List<Line> lines) {
        return lines.stream().map(Line::marks).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** A line of the rate card: {@code marks = perEntry * count}. */
    static Line line(String description, BigDecimal perEntry, int count) {
        return new Line(description, perEntry, count, perEntry.multiply(BigDecimal.valueOf(count)));
    }

    private static Line line(String description, int perEntry, int count) {
        return line(description, BigDecimal.valueOf(perEntry), count);
    }

    /** A line whose marks stop at {@code cap}: {@code marks = min(perEntry * count, cap)}. */
    static Line capped(String description, BigDecimal perEntry, int count, int cap) {
        BigDecimal marks = perEntry.multiply(BigDecimal.valueOf(count)).min(BigDecimal.valueOf(cap));
        return new Line(description, perEntry, count, marks, cap);
    }

    /** Marks as people write them: "5", "2.5", "20", never "20.0" or "2E+1". */
    public static String plain(BigDecimal marks) {
        return marks.stripTrailingZeros().toPlainString();
    }

    /**
     * Entries of this appraisal that match {@code condition} and whose date columns all fall in its academic year. A window
     * is {@code kind:column}: {@code date} (a day), {@code month} (YYYY-MM) or {@code year}. The tables, columns and
     * conditions are fixed text in this class, never input.
     */
    private int count(String table, String condition, long appraisalId, String... windows) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM ").append(table).append(" WHERE appraisal_id = ?");
        List<Object> params = new ArrayList<>();
        params.add(appraisalId);
        if (condition != null) sql.append(" AND ").append(condition);
        for (String w : windows) {
            String column = w.substring(w.indexOf(':') + 1);
            switch (w.substring(0, w.indexOf(':'))) {
                case "date" -> sql.append(" AND `").append(column).append("` BETWEEN ").append(START).append(" AND ").append(END);
                case "month" -> sql.append(" AND `").append(column).append("` BETWEEN DATE_FORMAT(").append(START)
                        .append(", '%Y-%m') AND DATE_FORMAT(").append(END).append(", '%Y-%m')");
                case "year" -> sql.append(" AND `").append(column).append("` BETWEEN YEAR(").append(START).append(") AND YEAR(").append(END).append(")");
                default -> throw new IllegalArgumentException(w);
            }
            params.add(appraisalId);
            params.add(appraisalId);
        }
        return jdbc.sql(sql.toString()).params(params).query(Integer.class).single();
    }
}
