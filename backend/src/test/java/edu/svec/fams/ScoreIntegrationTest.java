package edu.svec.fams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.svec.fams.appraisal.AppraisalService;
import edu.svec.fams.appraisal.WorkflowAction;
import edu.svec.fams.auth.FamsUserPrincipal;
import edu.svec.fams.auth.Role;
import edu.svec.fams.scoring.Criteria;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ScoreIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired TestDb db;
    @Autowired JdbcClient jdbc;
    @Autowired AppraisalService appraisals;
    @Autowired ObjectMapper json;

    FamsUserPrincipal faculty, lecturer, otherFaculty, hod, otherDeptHod, principal, admin;
    long id, lecturerAppraisal;

    @BeforeEach
    void setUp() {
        db.reset();
        faculty = db.faculty("f1@test.edu", "E001", "CSE", "ASST_PROF");      // maxima 30,12,10,15,15,5,3,5,5
        lecturer = db.faculty("lec@test.edu", "E004", "CSE", "LECTURER");     // funded projects and IPR: 0
        otherFaculty = db.faculty("f2@test.edu", "E002", "CSE", "PROFESSOR");
        hod = db.hod("hod@test.edu", "CSE");
        otherDeptHod = db.hod("hod2@test.edu", "ECE");
        principal = db.user("p@test.edu", Role.PRINCIPAL);
        admin = db.user("admin@test.edu", Role.ADMIN);
        id = appraisals.create(faculty);
        lecturerAppraisal = appraisals.create(lecturer);
    }

    private ResultActions save(FamsUserPrincipal who, long appraisal, Map<String, Object> scores) throws Exception {
        return mvc.perform(put("/api/appraisals/" + appraisal + "/scores").with(user(who)).with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("scores", scores))));
    }

    private static Map<String, Object> scores(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private BigDecimal stored(long appraisal, String criterion) {
        return jdbc.sql("SELECT self_score FROM appraisal_scores WHERE appraisal_id = ? AND criterion = ?")
                .params(appraisal, criterion).query(BigDecimal.class).optional().orElse(null);
    }

    private int audits(String action) {
        return jdbc.sql("SELECT COUNT(*) FROM audit_logs WHERE action = ? AND entity_id = ?")
                .params(action, id).query(Integer.class).single();
    }

    // ---- the sheet itself ----

    @Test
    void theSheetComesInTheFormsOrderWithItsOwnWording() throws Exception {
        mvc.perform(get("/api/appraisals/" + id).with(user(faculty)))
                .andExpect(jsonPath("$.scores.length()").value(9))
                .andExpect(jsonPath("$.scores[0].criterion").value("TEACHING_LEARNING"))
                .andExpect(jsonPath("$.scores[0].label").value("Teaching & Learning"))
                .andExpect(jsonPath("$.scores[0].maxMarks").value(40))
                .andExpect(jsonPath("$.scores[4].maxMarks").isEmpty()) // B5 to B9 are marked per entry: no maximum
                .andExpect(jsonPath("$.scores[3].label").value("Administrative, Curriculum & Quality Contributions"))
                .andExpect(jsonPath("$.scores[8].label").value("Professional Memberships, Awards & Recognitions"))
                .andExpect(jsonPath("$.scores[0].selfScore").doesNotExist());
    }

    @Test
    void everyCriterionTheDatabaseSeedsIsKnownToTheCodeAndViceVersa() {
        List<String> seeded = jdbc.sql("SELECT DISTINCT criterion FROM scoring_policy_criteria").query(String.class).list();
        for (String code : seeded) assertTrue(Criteria.order(code) < Integer.MAX_VALUE, code + " has no label");
        assertEquals(Criteria.values().length, seeded.size());
    }

    // ---- saving ----

    @Test
    void facultyCanEnterScoresAndTheyShowOnTheSheet() throws Exception {
        save(faculty, id, scores("TEACHING_LEARNING", 25.5, "OUTREACH", 4, "STUDENT_MENTORING", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].selfScore").value(25.5))
                .andExpect(jsonPath("$[1].selfScore").value(10.0))
                .andExpect(jsonPath("$[7].selfScore").value(4.0));
        mvc.perform(get("/api/appraisals/" + id).with(user(faculty)))
                .andExpect(jsonPath("$.scores[0].selfScore").value(25.5));
        assertEquals(0, new BigDecimal("25.50").compareTo(stored(id, "TEACHING_LEARNING")));
    }

    @Test
    void aScoreEqualToTheMaximumOrZeroIsAccepted() throws Exception {
        save(faculty, id, scores("TEACHING_LEARNING", 40, "OUTREACH", 0)).andExpect(status().isOk());
        assertEquals(0, new BigDecimal("40").compareTo(stored(id, "TEACHING_LEARNING")));
        assertEquals(0, BigDecimal.ZERO.compareTo(stored(id, "OUTREACH")));
    }

    @Test
    void savingOnlyTouchesTheCriteriaSentAndNullClears() throws Exception {
        save(faculty, id, scores("TEACHING_LEARNING", 20, "OUTREACH", 3)).andExpect(status().isOk());
        save(faculty, id, scores("OUTREACH", null)).andExpect(status().isOk());
        assertEquals(0, new BigDecimal("20").compareTo(stored(id, "TEACHING_LEARNING")));   // untouched
        assertEquals(null, stored(id, "OUTREACH"));                                         // cleared
        save(faculty, id, scores("TEACHING_LEARNING", "")).andExpect(status().isOk());       // blank also clears
        assertEquals(null, stored(id, "TEACHING_LEARNING"));
    }

    // ---- validation ----

    @Test
    void aScoreAboveTheCadreMaximumNamesTheCriterionAndTheLimit() throws Exception {
        save(faculty, id, scores("TEACHING_LEARNING", 41))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors['scores.TEACHING_LEARNING']")
                        .value("Self-score for Teaching & Learning must be between 0 and 40."));
        save(lecturer, lecturerAppraisal, scores("TEACHING_LEARNING", 41)).andExpect(status().isBadRequest());
        save(lecturer, lecturerAppraisal, scores("TEACHING_LEARNING", 40)).andExpect(status().isOk());
    }

    @Test
    void negativeNonNumericAndOverPreciseValuesAreRejected() throws Exception {
        save(faculty, id, scores("OUTREACH", -1)).andExpect(status().isBadRequest());
        save(faculty, id, scores("OUTREACH", "abc")).andExpect(jsonPath("$.fieldErrors['scores.OUTREACH']")
                .value("Self-score for Outreach must be a number."));
        save(faculty, id, scores("OUTREACH", 2.555)).andExpect(jsonPath("$.fieldErrors['scores.OUTREACH']")
                .value("Self-score for Outreach can have at most 2 decimal places."));
        save(faculty, id, scores("OUTREACH", true)).andExpect(status().isBadRequest());
        save(faculty, id, scores("OUTREACH", "1E999999999")).andExpect(status().isBadRequest());
    }

    @Test
    void theCriteriaMarkedPerEntryHaveNoMaximumForAnyCadre() throws Exception {
        save(lecturer, lecturerAppraisal, scores("FUNDED_PROJECTS", 500, "RESEARCH_PUBLICATIONS", 1234.5)).andExpect(status().isOk());
        save(faculty, id, scores("OUTREACH", 250)).andExpect(status().isOk());
        assertEquals(0, new BigDecimal("250").compareTo(stored(id, "OUTREACH")));
    }

    @Test
    void marksAreCalculatedFromTheEntriesLiveAndATypedScoreOverridesThem() throws Exception {
        jdbc.sql("INSERT INTO outreach (appraisal_id, role, event_activity, organization, event_date) VALUES (?, 'RESOURCE_PERSON', 'Talk', 'X', '2026-01-10')")
                .params(id).update();
        jdbc.sql("INSERT INTO outreach (appraisal_id, role, event_activity, organization, event_date) VALUES (?, 'RESOURCE_PERSON', 'Talk 2', 'X', '2026-02-10')")
                .params(id).update();
        jdbc.sql("INSERT INTO memberships_awards (appraisal_id, item, awarding_body, level, year) VALUES (?, 'IEEE', 'IEEE', 'INTL', 2025)").params(id).update();
        jdbc.sql("INSERT INTO books (appraisal_id, authors, title, publisher, month_year, type) VALUES (?, 'A', 'B', 'P', '2025-05', 'BOOK')").params(id).update();
        jdbc.sql("INSERT INTO books (appraisal_id, authors, title, publisher, month_year, type) VALUES (?, 'A', 'C', 'P', '2025-06', 'CHAPTER')").params(id).update();
        String paper = "INSERT INTO journal_publications (appraisal_id, title, author_position, journal, month_year, indexing) VALUES (?, 'T', '1', 'J', '2025-06', ?)";
        jdbc.sql(paper).params(id, "SCI_SCIE").update();
        jdbc.sql(paper).params(id, "OTHERS").update();
        for (String status : new String[] {"SANCTIONED", "APPLIED", "APPLIED"}) {
            jdbc.sql("INSERT INTO funded_projects (appraisal_id, title, role, type, funding_agency_client, amount, start_date, end_date, status, year)"
                    + " VALUES (?, 'P', 'PI', 'RESEARCH', 'AICTE', 100000, '2025-01-01', '2025-12-31', ?, 2025)").params(id, status).update();
        }
        mvc.perform(get("/api/appraisals/" + id).with(user(faculty)))
                .andExpect(jsonPath("$.scores[4].calculated").value(15.0))     // one SCI/SCIE paper; the "others" paper earns nothing
                .andExpect(jsonPath("$.scores[5].calculated").value(60.0))     // one sanctioned (50) and two applied (5 each)
                .andExpect(jsonPath("$.scores[6].calculated").value(25.0))     // a book (20) and a chapter (5)
                .andExpect(jsonPath("$.scores[7].calculated").value(10.0))     // two outreach entries
                .andExpect(jsonPath("$.scores[8].calculated").value(5.0))
                .andExpect(jsonPath("$.scores[5].score").value(60.0))
                .andExpect(jsonPath("$.scores[5].selfScore").doesNotExist())
                .andExpect(jsonPath("$.scores[5].breakdown[0].count").value(1))
                .andExpect(jsonPath("$.scores[5].breakdown[1].marks").value(10));
        // the faculty member may adjust; clearing it returns to the calculation
        save(faculty, id, scores("FUNDED_PROJECTS", 40)).andExpect(status().isOk());
        mvc.perform(get("/api/appraisals/" + id).with(user(faculty)))
                .andExpect(jsonPath("$.scores[5].score").value(40.0)).andExpect(jsonPath("$.scores[5].calculated").value(60.0));
        save(faculty, id, scores("FUNDED_PROJECTS", null)).andExpect(status().isOk());
        mvc.perform(get("/api/appraisals/" + id).with(user(faculty))).andExpect(jsonPath("$.scores[5].score").value(60.0));
    }

    @Test
    void teachingWorkloadIsTwoAndAHalfMarksACourseUpToItsLimit() throws Exception {
        for (int i = 1; i <= 10; i++) {   // 8 courses (4 a semester, 2 semesters) earn the 20; more add nothing
            jdbc.sql("INSERT INTO teaching_courses (appraisal_id, course_code, course_name, course_type, program, branch, semester, sections, hours_per_week, pass_percentage)"
                    + " VALUES (?, ?, 'Intro', 'THEORY', 'B.Tech', 'CSE', 3, 1, 4, 90)").params(id, "C" + i).update();
            mvc.perform(get("/api/appraisals/" + id).with(user(faculty)))
                    .andExpect(jsonPath("$.scores[0].components[0].awarded").value(Math.min(20.0, 2.5 * i)))
                    .andExpect(jsonPath("$.scores[0].calculated").value(Math.min(20.0, 2.5 * i)));
        }
    }

    @Test
    void anUnknownCriterionIsRejected() throws Exception {
        save(faculty, id, scores("MAGIC", 5)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors['scores.MAGIC']").value("Not a recognised criterion."));
    }

    @Test
    void oneBadValueSavesNothingAtAll() throws Exception {
        save(faculty, id, scores("TEACHING_LEARNING", 10)).andExpect(status().isOk());
        save(faculty, id, scores("TEACHING_LEARNING", 12, "OUTREACH", 3, "STUDENT_MENTORING", 99)).andExpect(status().isBadRequest());
        assertEquals(0, new BigDecimal("10").compareTo(stored(id, "TEACHING_LEARNING"))); // the valid one was not applied
        assertEquals(null, stored(id, "OUTREACH"));
    }

    @Test
    void aMissingScoresObjectIsAClientError() throws Exception {
        mvc.perform(put("/api/appraisals/" + id + "/scores").with(user(faculty)).with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.scores").value("scores is required."));
    }

    // ---- the maximum is a snapshot, not the editable part ----

    @Test
    void theMaximumCannotBeChangedThroughThisEndpoint() throws Exception {
        save(faculty, id, scores("TEACHING_LEARNING", 20)).andExpect(status().isOk());
        Integer max = jdbc.sql("SELECT max_marks FROM appraisal_scores WHERE appraisal_id = ? AND criterion = 'TEACHING_LEARNING'")
                .param(id).query(Integer.class).single();
        assertEquals(40, max);
    }

    // ---- locking and access ----

    @Test
    void scoresAreLockedOnceSubmittedAndStayLocked() throws Exception {
        save(faculty, id, scores("OUTREACH", 3)).andExpect(status().isOk());
        appraisals.submit(id, faculty);
        save(faculty, id, scores("OUTREACH", 4)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This appraisal is locked while it is SUBMITTED."));

        appraisals.transition(id, hod, WorkflowAction.Verb.START, null);
        appraisals.transition(id, hod, WorkflowAction.Verb.APPROVE, null);
        save(faculty, id, scores("OUTREACH", 4)).andExpect(status().isConflict());
        assertEquals(0, new BigDecimal("3").compareTo(stored(id, "OUTREACH")));
    }

    @Test
    void reviewersAndOthersCannotWriteScores() throws Exception {
        appraisals.submit(id, faculty);
        save(hod, id, scores("OUTREACH", 1)).andExpect(status().isForbidden());
        save(otherDeptHod, id, scores("OUTREACH", 1)).andExpect(status().isNotFound());
        save(otherFaculty, id, scores("OUTREACH", 1)).andExpect(status().isNotFound());
        save(principal, id, scores("OUTREACH", 1)).andExpect(status().isNotFound());
        save(admin, id, scores("OUTREACH", 1)).andExpect(status().isNotFound());
        assertEquals(null, stored(id, "OUTREACH"));
    }

    @Test
    void anotherFacultyCannotScoreMyAppraisalEvenWhileItIsADraft() throws Exception {
        save(otherFaculty, id, scores("OUTREACH", 1)).andExpect(status().isNotFound());
        assertEquals(null, stored(id, "OUTREACH"));
    }

    @Test
    void hodSeesTheSelfScoresReadOnly() throws Exception {
        save(faculty, id, scores("OUTREACH", 4)).andExpect(status().isOk());
        appraisals.submit(id, faculty);
        mvc.perform(get("/api/appraisals/" + id).with(user(hod)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scores[7].selfScore").value(4.0));
    }

    @Test
    void writingWithoutACsrfTokenIsRejected() throws Exception {
        mvc.perform(put("/api/appraisals/" + id + "/scores").with(user(faculty))
                .contentType(MediaType.APPLICATION_JSON).content("{\"scores\":{\"OUTREACH\":1}}"))
                .andExpect(status().isForbidden());
        assertEquals(null, stored(id, "OUTREACH"));
    }

    // ---- audit and last-saved ----

    @Test
    void onlyRealChangesAreAuditedSoAutosaveDoesNotFloodTheTrail() throws Exception {
        save(faculty, id, scores("OUTREACH", 3)).andExpect(status().isOk());
        assertEquals(1, audits("SCORES_SAVED"));
        save(faculty, id, scores("OUTREACH", 3.00)).andExpect(status().isOk());    // same value, different spelling
        save(faculty, id, scores("OUTREACH", "3")).andExpect(status().isOk());
        assertEquals(1, audits("SCORES_SAVED"));
        save(faculty, id, scores("OUTREACH", 4)).andExpect(status().isOk());
        assertEquals(2, audits("SCORES_SAVED"));
    }

    @Test
    void savingAScoreUpdatesTheAppraisalsLastSavedTime() throws Exception {
        jdbc.sql("UPDATE appraisals SET updated_at = '2020-01-01 00:00:00' WHERE id = ?").param(id).update();
        save(faculty, id, scores("OUTREACH", 3)).andExpect(status().isOk());
        Integer year = jdbc.sql("SELECT YEAR(updated_at) FROM appraisals WHERE id = ?").param(id)
                .query(Integer.class).single();
        assertTrue(year >= 2026);
    }
}
