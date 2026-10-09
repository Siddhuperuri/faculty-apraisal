package edu.svec.fams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import edu.svec.fams.auth.FamsUserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/** The database is the last line of defence: these rules must hold even if application code is wrong. */
@SpringBootTest
@ActiveProfiles("test")
class SchemaIntegrityTest {

    @Autowired TestDb db;
    @Autowired JdbcClient jdbc;
    @Autowired edu.svec.fams.appraisal.AppraisalService service;

    long appraisalId;
    FamsUserPrincipal faculty;

    @BeforeEach
    void setUp() {
        db.reset();
        faculty = db.faculty("f1@test.edu", "E001", "CSE", "ASST_PROF");
        appraisalId = service.create(faculty);
        service.submit(appraisalId, faculty);
    }

    private void assertRejected(String sql, Object... params) {
        assertThrows(DataAccessException.class, () -> jdbc.sql(sql).params(params).update());
    }

    @Test
    void everySeededCadrePolicyHasNineCriteriaAndNoMaximumForThoseMarkedPerEntry() {
        var rows = jdbc.sql("""
                SELECT c.code, SUM(CASE WHEN k.criterion IN ('RESEARCH_PUBLICATIONS', 'FUNDED_PROJECTS', 'PATENTS_BOOKS_IPR', 'OUTREACH', 'MEMBERSHIPS_AWARDS')
                                        THEN k.max_marks ELSE 0 END) AS perEntry, COUNT(*) AS n
                FROM scoring_policy_criteria k
                JOIN scoring_policies p ON p.id = k.policy_id
                JOIN cadres c ON c.id = p.cadre_id
                WHERE p.version = 1 GROUP BY c.code""")
                .query((rs, i) -> rs.getString("code") + ":" + rs.getInt("perEntry") + ":" + rs.getInt("n"))
                .list();
        assertEquals(5, rows.size(), "five cadres expected: " + rows);
        for (String r : rows) assertEquals(true, r.endsWith(":0:9"), r);
    }

    @Test
    void supportingDocumentsAreGoneFromTheSchema() {
        int tables = jdbc.sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'supporting_documents'")
                .query(Integer.class).single();
        assertEquals(0, tables, "Section 12 was removed in V15");
    }

    @Test
    void selfScoreCannotBeNegative() {
        assertRejected("UPDATE appraisal_scores SET self_score = -1 WHERE appraisal_id = ? AND criterion = 'TEACHING_LEARNING'", appraisalId);
    }

    @Test
    void selfScoreWithinRangeIsAccepted() {
        int n = jdbc.sql("UPDATE appraisal_scores SET self_score = max_marks WHERE appraisal_id = ? AND criterion = 'TEACHING_LEARNING'")
                .param(appraisalId).update();
        assertEquals(1, n);
    }

    @Test
    void auditLogCannotBeEdited() {
        assertRejected("UPDATE audit_logs SET action = 'TAMPERED'");
    }

    @Test
    void reviewHistoryCannotBeUpdatedOrDeleted() {
        assertRejected("UPDATE review_actions SET comment = 'edited'");
        assertRejected("DELETE FROM review_actions");
    }

    @Test
    void invalidStatusIsRejected() {
        assertRejected("UPDATE appraisals SET status = 'WHATEVER' WHERE id = ?", appraisalId);
    }

    @Test
    void approvedRequiresAnApprovalTimestampAndViceVersa() {
        assertRejected("UPDATE appraisals SET status = 'APPROVED' WHERE id = ?", appraisalId);
        assertRejected("UPDATE appraisals SET final_approved_at = CURRENT_TIMESTAMP WHERE id = ?", appraisalId);
    }

    @Test
    void invalidRoleIsRejected() {
        assertRejected("INSERT INTO users (email, role) VALUES ('x@test.edu', 'SUPERUSER')");
    }

    @Test
    void duplicateEmailIgnoringCaseIsRejected() {
        assertRejected("INSERT INTO users (email, role) VALUES ('F1@TEST.EDU', 'FACULTY')");
    }

    @Test
    void sectionRowsEnforceRangesEnumsAndFormats() {
        String teaching = "INSERT INTO teaching_courses (appraisal_id, course_code, course_name, course_type, program, branch,"
                + " semester, sections, hours_per_week, pass_percentage) VALUES (?, 'CS101', 'Intro', ?, 'BTech', 'CSE', 3, 1, 4, ?)";
        assertEquals(1, jdbc.sql(teaching).params(appraisalId, "THEORY", 95).update());
        assertRejected(teaching, appraisalId, "THEORY", 101);      // pass % > 100
        assertRejected(teaching, appraisalId, "SEMINAR", 90);      // not THEORY/LAB

        String journal = "INSERT INTO journal_publications (appraisal_id, title, author_position, journal, month_year, indexing)"
                + " VALUES (?, 'T', 'First', 'J', ?, 'SCOPUS')";
        assertEquals(1, jdbc.sql(journal).params(appraisalId, "2026-09").update());
        assertRejected(journal, appraisalId, "2026-13");           // month 13
        assertRejected(journal, appraisalId, "09/2026");           // wrong format

        assertRejected("INSERT INTO fdps (appraisal_id, title, mode, institution_venue, start_date, end_date, days)"
                + " VALUES (?, 'F', 'ONLINE', 'V', '2026-09-10', '2026-09-01', 2)", appraisalId); // end < start
    }

    @Test
    void sectionRecordsCascadeWhenADraftIsRemovedButReviewedWorkIsProtected() {
        jdbc.sql("INSERT INTO student_mentoring (appraisal_id, total_students_mentored) VALUES (?, 10)")
                .param(appraisalId).update();
        // This appraisal has review history (a submit), so it cannot be deleted.
        assertRejected("DELETE FROM appraisals WHERE id = ?", appraisalId);
    }
}
