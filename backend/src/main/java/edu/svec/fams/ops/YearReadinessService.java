package edu.svec.fams.ops;

import edu.svec.fams.appraisal.AppraisalStatus;
import edu.svec.fams.common.ApiException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The guided checklist for opening or closing an academic year: what still stands in the way, in plain words, with the
 * counts behind each line. It advises and never blocks: the administrator decides, having seen it.
 */
@Service
public class YearReadinessService {

    /** @param level ok, warn or problem */
    public record Item(String code, String level, String message) {}

    /**
     * @param mode CLOSING while the year is open, OPENING while it is closed
     * @param byState appraisals of the year by workflow state, plus NOT_STARTED for active faculty with none
     */
    public record Readiness(long yearId, String name, boolean active, String mode, Map<String, Integer> byState, int queryRaised,
                            List<Item> items, boolean ready) {}

    private final JdbcClient jdbc;

    public YearReadinessService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Readiness readiness(long yearId) {
        var year = jdbc.sql("SELECT name, active FROM academic_years WHERE id = ?").param(yearId)
                .query((rs, n) -> Map.entry(rs.getString(1), rs.getBoolean(2))).optional().orElseThrow(ApiException::notFound);
        boolean active = year.getValue();

        Map<String, Integer> byState = new LinkedHashMap<>();
        byState.put("NOT_STARTED", 0);
        for (AppraisalStatus s : AppraisalStatus.values()) byState.put(s.name(), 0);
        int[] started = {0};
        jdbc.sql("SELECT status, COUNT(*) FROM appraisals WHERE academic_year_id = ? GROUP BY status").param(yearId).query((rs, n) -> {
            byState.merge(rs.getString(1), rs.getInt(2), Integer::sum);
            started[0] += rs.getInt(2);
            return null;
        }).list();
        int faculty = jdbc.sql("SELECT COUNT(*) FROM users WHERE role = 'FACULTY' AND status = 'ACTIVE'").query(Integer.class).single();
        int notStarted = Math.max(0, faculty - jdbc.sql("""
                SELECT COUNT(*) FROM appraisals a JOIN faculty_profiles fp ON fp.id = a.faculty_id JOIN users u ON u.id = fp.user_id
                WHERE a.academic_year_id = ? AND u.status = 'ACTIVE'""").param(yearId).query(Integer.class).single());
        byState.put("NOT_STARTED", notStarted);
        int queries = jdbc.sql("""
                SELECT COUNT(*) FROM appraisals a WHERE a.academic_year_id = ? AND a.status = 'HOD_REVIEW'
                  AND EXISTS (SELECT 1 FROM appraisal_messages m WHERE m.appraisal_id = a.id)""").param(yearId).query(Integer.class).single();

        List<Item> items = new ArrayList<>();
        // Policy copies: every open cadre needs a scoring policy for the year, or its faculty cannot start.
        List<String> uncovered = jdbc.sql("""
                SELECT c.name FROM cadres c WHERE c.active = TRUE
                  AND NOT EXISTS (SELECT 1 FROM scoring_policies p WHERE p.academic_year_id = ? AND p.cadre_id = c.id AND p.active = TRUE)
                ORDER BY c.name""").param(yearId).query(String.class).list();
        items.add(uncovered.isEmpty()
                ? new Item("POLICIES", "ok", "Every designation has a scoring policy for this year.")
                : new Item("POLICIES", active ? "warn" : "problem", "No scoring policy for this year for: " + String.join(", ", uncovered)
                        + ". Faculty of these designations cannot start an appraisal until one is published."));

        int drafts = byState.get("DRAFT");
        int inReview = byState.get("SUBMITTED") + byState.get("HOD_REVIEW") + byState.get("HOD_APPROVED") + byState.get("PRINCIPAL_REVIEW");
        if (active) {
            items.add(drafts == 0 ? new Item("DRAFTS", "ok", "No drafts are waiting to be submitted.")
                    : new Item("DRAFTS", "warn", count(drafts, "draft") + " not submitted yet. Closing the year stops new appraisals; "
                            + "drafts already started can still be finished, but nobody can start another."));
            items.add(inReview == 0 ? new Item("REVIEWS", "ok", "No appraisal is waiting for a Head of Department, the Principal or the Director Technical.")
                    : new Item("REVIEWS", "warn", count(inReview, "appraisal") + " still in review (" + byState.get("SUBMITTED") + " submitted, "
                            + byState.get("HOD_REVIEW") + " with the HoD, " + byState.get("HOD_APPROVED") + " approved by the HoD, "
                            + byState.get("PRINCIPAL_REVIEW") + " with the Principal or Director Technical). Reviews continue after the year is closed."));
            if (queries > 0) items.add(new Item("QUERIES", "warn", count(queries, "appraisal") + " waiting for a faculty member to meet the HoD."));
            if (notStarted > 0) {
                items.add(new Item("NOT_STARTED", "warn", notStarted + " active faculty " + (notStarted == 1 ? "member has" : "members have")
                        + " not started an appraisal this year."));
            }
            int others = jdbc.sql("SELECT COUNT(*) FROM academic_years WHERE active = TRUE AND id <> ?").param(yearId).query(Integer.class).single();
            items.add(others > 0 ? new Item("NEXT_YEAR", "ok", "Another academic year is open for new appraisals.")
                    : new Item("NEXT_YEAR", "warn", "No other academic year is open. After closing this one, faculty cannot start an appraisal until you open the next."));
        } else {
            int total = started[0];
            items.add(total == 0 ? new Item("EMPTY", "ok", "No appraisals exist for this year yet.")
                    : new Item("EXISTING", "warn", count(total, "appraisal") + " already exist for this year. Reopening lets faculty without one start theirs."));
            int dated = jdbc.sql("SELECT COUNT(*) FROM academic_years WHERE id = ? AND end_date >= CURRENT_DATE").param(yearId).query(Integer.class).single();
            items.add(dated > 0 ? new Item("DATES", "ok", "The year has not ended yet.")
                    : new Item("DATES", "warn", "The year's end date has passed. Check this is the year you mean to open."));
        }
        boolean ready = items.stream().allMatch(i -> i.level().equals("ok"));
        return new Readiness(yearId, year.getKey(), active, active ? "CLOSING" : "OPENING", byState, queries, items, ready);
    }

    private static String count(int n, String noun) {
        return n + " " + noun + (n == 1 ? " is" : "s are");
    }
}
