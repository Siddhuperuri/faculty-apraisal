package edu.svec.fams.scoring;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The marks the college gives for each entry of B5 to B9, added up from what the faculty member has entered. They are
 * the same for every cadre and have no upper limit. B1 (2.5 marks for each course) is worked out by {@link ScoreService},
 * because its limit depends on the cadre's policy; B2 to B4 have no per-entry marks and are typed in.
 *
 * <p>The sections an entry is counted from, and what counts, are the mapping agreed with the college: a journal paper
 * by its indexing (UGC-CARE and others earn nothing), Ph.D. scholars awarded are "guided" and registered or submitted
 * are "guiding", M.Tech and MBA scholars awarded are "PG guided", and each UG project is "B.Tech guided".
 */
@Component
public class ScoringRules {

    /** One line of the rate card with what the faculty member has: {@code marks = perEntry * count}. */
    public record Line(String description, BigDecimal perEntry, int count, BigDecimal marks) {}

    private final JdbcClient jdbc;

    public ScoringRules(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The lines of every per-entry criterion (B5 to B9), keyed by criterion code. */
    public Map<String, List<Line>> lines(long appraisalId) {
        Map<String, List<Line>> out = new LinkedHashMap<>();

        List<Line> research = new ArrayList<>();
        research.add(line("SCI/SCIE journal paper", 15, count("journal_publications", "indexing = 'SCI_SCIE'", appraisalId)));
        research.add(line("ESCI/Scopus journal paper", 10, count("journal_publications", "indexing = 'SCOPUS'", appraisalId)));
        research.add(line("Conference paper", 5, count("conference_papers", null, appraisalId)));
        research.add(line("Ph.D. guided (awarded)", 10, count("research_scholars", "degree = 'PHD' AND status = 'AWARDED'", appraisalId)));
        research.add(line("Ph.D. guiding (registered or submitted)", 5,
                count("research_scholars", "degree = 'PHD' AND status IN ('REGISTERED', 'SUBMITTED')", appraisalId)));
        research.add(line("PG guided (M.Tech/MBA awarded)", 3,
                count("research_scholars", "degree IN ('MTECH', 'MBA') AND status = 'AWARDED'", appraisalId)));
        research.add(line("B.Tech project guided", 2, count("student_projects", "level = 'UG'", appraisalId)));
        out.put("RESEARCH_PUBLICATIONS", research);

        out.put("FUNDED_PROJECTS", List.of(
                line("Project sanctioned", 50, count("funded_projects", "status = 'SANCTIONED'", appraisalId)),
                line("Project applied for (not yet sanctioned)", 5, count("funded_projects", "status = 'APPLIED'", appraisalId))));

        out.put("PATENTS_BOOKS_IPR", List.of(
                line("Patent published", 5, count("patents_ipr", "status = 'PUBLISHED'", appraisalId)),
                line("Patent granted", 20, count("patents_ipr", "status = 'GRANTED'", appraisalId)),
                line("Book published", 20, count("books", "type = 'BOOK'", appraisalId)),
                line("Book chapter published", 5, count("books", "type = 'CHAPTER'", appraisalId))));

        out.put("OUTREACH", List.of(line("Outreach entry", 5, count("outreach", null, appraisalId))));
        out.put("MEMBERSHIPS_AWARDS", List.of(line("Membership, award or recognition", 5, count("memberships_awards", null, appraisalId))));
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

    /** Marks as people write them: "5", "2.5", "20", never "20.0" or "2E+1". */
    public static String plain(BigDecimal marks) {
        return marks.stripTrailingZeros().toPlainString();
    }

    /** The table and condition are fixed text above, never input. */
    private int count(String table, String condition, long appraisalId) {
        String sql = "SELECT COUNT(*) FROM " + table + " WHERE appraisal_id = ?" + (condition == null ? "" : " AND " + condition);
        return jdbc.sql(sql).param(appraisalId).query(Integer.class).single();
    }
}
