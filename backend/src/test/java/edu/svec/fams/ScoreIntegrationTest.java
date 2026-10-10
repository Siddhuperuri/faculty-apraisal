package edu.svec.fams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import edu.svec.fams.appraisal.AppraisalService;
import edu.svec.fams.appraisal.WorkflowAction;
import edu.svec.fams.auth.FamsUserPrincipal;
import edu.svec.fams.auth.Role;
import edu.svec.fams.scoring.Criteria;
import java.util.List;
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

/** The score sheet: every mark is worked out from the entries (nobody types one), live, whenever the appraisal is read. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ScoreIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired TestDb db;
    @Autowired JdbcClient jdbc;
    @Autowired AppraisalService appraisals;

    FamsUserPrincipal faculty, professor, hod, principal;
    long id, professorAppraisal;

    @BeforeEach
    void setUp() {
        db.reset();
        faculty = db.faculty("f1@test.edu", "E001", "CSE", "ASST_PROF");      // maxima 40, 15, 15, 15
        professor = db.faculty("prof@test.edu", "E002", "CSE", "PROFESSOR");  // B1 maximum 30
        hod = db.hod("hod@test.edu", "CSE");
        principal = db.user("p@test.edu", Role.PRINCIPAL);
        id = appraisals.create(faculty);
        professorAppraisal = appraisals.create(professor);
    }

    private ResultActions sheet(FamsUserPrincipal who, long appraisal) throws Exception {
        return mvc.perform(get("/api/appraisals/" + appraisal).with(user(who)));
    }

    private void insertCourses(long appraisal, int n) {
        for (int i = 1; i <= n; i++) {
            jdbc.sql("INSERT INTO teaching_courses (appraisal_id, course_code, course_name, course_type, program, branch, semester, hours_per_week, pass_percentage)"
                    + " VALUES (?, ?, 'Intro', 'THEORY', 'B_TECH', 'CSE', 3, 4, 90)").params(appraisal, "C" + i).update();
        }
    }

    // ---- the sheet itself ----

    @Test
    void theSheetComesInTheFormsOrderWithItsOwnWordingAndNothingTyped() throws Exception {
        sheet(faculty, id)
                .andExpect(jsonPath("$.scores.length()").value(9))
                .andExpect(jsonPath("$.scores[0].criterion").value("TEACHING_LEARNING"))
                .andExpect(jsonPath("$.scores[0].label").value("Teaching & Learning"))
                .andExpect(jsonPath("$.scores[0].maxMarks").value(40))
                .andExpect(jsonPath("$.scores[4].maxMarks").isEmpty()) // B5 to B9 are marked per entry: no maximum
                .andExpect(jsonPath("$.scores[3].label").value("Administrative, Curriculum & Quality Contributions"))
                .andExpect(jsonPath("$.scores[8].label").value("Professional Memberships, Awards & Recognitions"))
                .andExpect(jsonPath("$.scores[0].selfScore").doesNotExist())
                .andExpect(jsonPath("$.scores[0].score").value(0));
    }

    @Test
    void everyCriterionTheDatabaseSeedsIsKnownToTheCode() {
        List<String> seeded = jdbc.sql("SELECT DISTINCT criterion FROM scoring_policy_criteria").query(String.class).list();
        for (String code : seeded) assertTrue(Criteria.order(code) < Integer.MAX_VALUE, code + " has no label");
        for (Criteria c : Criteria.values()) assertTrue(seeded.contains(c.name()), c + " is not seeded");
    }

    @Test
    void thereIsNoWayToTypeAScore() throws Exception {
        mvc.perform(put("/api/appraisals/" + id + "/scores").with(user(faculty)).with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON).content("{\"scores\":{\"OUTREACH\":99}}"))
                .andExpect(status().is4xxClientError());
        sheet(faculty, id).andExpect(jsonPath("$.scores[7].score").value(0));
    }

    // ---- B1: one eighth of the maximum for each course ----

    @Test
    void eachCourseEarnsAnEighthOfTheCadresTeachingMaximumUpToEightCourses() throws Exception {
        for (int i = 1; i <= 8; i++) {
            insertCourses(id, 1);   // C1 again and again: the code is not unique
            sheet(faculty, id)
                    .andExpect(jsonPath("$.scores[0].score").value(5.0 * i))                          // 40 / 8 = 5 a course
                    .andExpect(jsonPath("$.scores[0].components[0].awarded").value(2.5 * i))           // workload 20 / 8
                    .andExpect(jsonPath("$.scores[0].breakdown[0].count").value(i));
        }
        sheet(faculty, id).andExpect(jsonPath("$.scores[0].score").value(40));
    }

    @Test
    void theTeachingMarksFollowTheCadresMaximum() throws Exception {
        insertCourses(professorAppraisal, 4);
        sheet(professor, professorAppraisal)
                .andExpect(jsonPath("$.scores[0].maxMarks").value(30))
                .andExpect(jsonPath("$.scores[0].score").value(15));   // half the courses, half of 30
    }

    @Test
    void marksNeverExceedTheMaximumEvenIfMoreCoursesGotInByAnOlderRoute() throws Exception {
        insertCourses(id, 10);
        sheet(faculty, id).andExpect(jsonPath("$.scores[0].score").value(40));
    }

    // ---- B2 to B4 ----

    @Test
    void mentoringMarksComeFromMenteesProjectsAndAchievements() throws Exception {
        jdbc.sql("INSERT INTO student_mentoring (appraisal_id, total_students_mentored) VALUES (?, 25)").params(id).update();
        for (int i = 0; i < 6; i++) {   // four earn marks; the rest are over the 4 for project guidance
            jdbc.sql("INSERT INTO student_projects (appraisal_id, level, title, student_count, outcome) VALUES (?, 'PG', 'P', 2, 'NONE')").params(id).update();
        }
        for (int i = 0; i < 2; i++) {
            jdbc.sql("INSERT INTO student_achievements (appraisal_id, student_name, roll_no, achievement, level, month_year) VALUES (?, 'S', 'R', 'Won', 'INST', '2025-09')")
                    .params(id).update();
        }
        sheet(faculty, id)
                .andExpect(jsonPath("$.scores[1].breakdown[0].marks").value(3.0))   // 25 of 50 mentees: half of 6
                .andExpect(jsonPath("$.scores[1].breakdown[1].marks").value(4))     // six projects, capped at 4
                .andExpect(jsonPath("$.scores[1].breakdown[2].marks").value(2))
                .andExpect(jsonPath("$.scores[1].score").value(9.0));
    }

    @Test
    void fdpAndCertificationMarks() throws Exception {
        for (int i = 0; i < 2; i++) {
            jdbc.sql("INSERT INTO fdps (appraisal_id, title, mode, institution_venue, start_date, end_date, days) VALUES (?, 'F', 'ONLINE', 'V', '2025-07-01', '2025-07-02', 2)")
                    .params(id).update();
        }
        for (int i = 0; i < 3; i++) {
            jdbc.sql("INSERT INTO certifications (appraisal_id, platform, title, start_date, end_date) VALUES (?, 'NPTEL', 'C', '2025-08-01', '2025-10-01')")
                    .params(id).update();
        }
        sheet(faculty, id).andExpect(jsonPath("$.scores[2].score").value(8));   // 2 x 1 + 3 x 2
    }

    @Test
    void administrativeMarksCountDepartmentInstituteAndEvents() throws Exception {
        String role = "INSERT INTO administrative_roles (appraisal_id, scope, role, from_date, to_date) VALUES (?, ?, 'R', '2025-06-01', '2026-05-31')";
        jdbc.sql(role).params(id, "DEPARTMENT").update();
        jdbc.sql(role).params(id, "INSTITUTE").update();
        for (int i = 0; i < 3; i++) {
            jdbc.sql("INSERT INTO events (appraisal_id, activity_type, role, title, start_date, end_date, beneficiaries)"
                    + " VALUES (?, 'Workshop', 'Convener', 'E', '2025-10-01', '2025-10-02', 50)")
                    .params(id).update();
        }
        sheet(faculty, id).andExpect(jsonPath("$.scores[3].score").value(6.5));   // 2 + 1.5 + 3
    }

    @Test
    void aKindOfEntryStopsEarningAtItsCapAndTheCriterionAtItsMaximum() throws Exception {
        for (int i = 0; i < 20; i++) {
            jdbc.sql("INSERT INTO certifications (appraisal_id, platform, title, start_date, end_date) VALUES (?, 'NPTEL', 'C', '2025-08-01', '2025-10-01')")
                    .params(id).update();
            jdbc.sql("INSERT INTO fdps (appraisal_id, title, mode, institution_venue, start_date, end_date, days) VALUES (?, 'F', 'ONLINE', 'V', '2025-07-01', '2025-07-02', 2)")
                    .params(id).update();
        }
        sheet(faculty, id).andExpect(jsonPath("$.scores[2].score").value(15))        // 5 + 10, and not a mark more
                .andExpect(jsonPath("$.scores[2].breakdown[1].marks").value(10));
    }

    // ---- B5 to B9 ----

    @Test
    void perEntryMarksAreCalculatedFromTheEntriesLive() throws Exception {
        jdbc.sql("INSERT INTO outreach (appraisal_id, role, event_activity, organization, event_date) VALUES (?, 'RESOURCE_PERSON', 'Talk', 'X', '2026-01-10')")
                .params(id).update();
        jdbc.sql("INSERT INTO outreach (appraisal_id, role, event_activity, organization, event_date) VALUES (?, 'RESOURCE_PERSON', 'Talk 2', 'X', '2026-02-10')")
                .params(id).update();
        jdbc.sql("INSERT INTO memberships_awards (appraisal_id, item, awarding_body, level, year) VALUES (?, 'IEEE', 'IEEE', 'INTL', 2025)").params(id).update();
        jdbc.sql("INSERT INTO books (appraisal_id, authors, title, publisher, month_year, type) VALUES (?, 'A', 'B', 'P', '2025-07', 'BOOK')").params(id).update();
        jdbc.sql("INSERT INTO books (appraisal_id, authors, title, publisher, month_year, type) VALUES (?, 'A', 'C', 'P', '2025-08', 'CHAPTER')").params(id).update();
        String paper = "INSERT INTO journal_publications (appraisal_id, title, author_position, journal, month_year, indexing) VALUES (?, 'T', '1', 'J', '2025-06', ?)";
        jdbc.sql(paper).params(id, "SCI_SCIE").update();
        jdbc.sql(paper).params(id, "OTHERS").update();
        for (String status : new String[] {"SANCTIONED", "APPLIED", "APPLIED"}) {
            jdbc.sql("INSERT INTO funded_projects (appraisal_id, title, role, type, funding_agency_client, amount, start_date, end_date, status, year)"
                    + " VALUES (?, 'P', 'PI', 'RESEARCH', 'AICTE', 100000, '2025-06-01', '2025-12-31', ?, 2025)").params(id, status).update();
        }
        sheet(faculty, id)
                .andExpect(jsonPath("$.scores[4].score").value(15.0))     // one SCI/SCIE paper; the "others" paper earns nothing
                .andExpect(jsonPath("$.scores[5].score").value(60.0))     // one sanctioned (50) and two applied (5 each)
                .andExpect(jsonPath("$.scores[6].score").value(25.0))     // a book (20) and a chapter (5)
                .andExpect(jsonPath("$.scores[7].score").value(10.0))     // two outreach entries
                .andExpect(jsonPath("$.scores[8].score").value(5.0))
                .andExpect(jsonPath("$.scores[5].breakdown[0].count").value(1))
                .andExpect(jsonPath("$.scores[5].breakdown[1].marks").value(10));
    }

    @Test
    void aFundedProjectOfAnotherYearEarnsNothing() throws Exception {
        String project = "INSERT INTO funded_projects (appraisal_id, title, role, type, funding_agency_client, amount, start_date, end_date, status, year)"
                + " VALUES (?, 'P', 'PI', 'RESEARCH', 'AICTE', 100000, '2024-01-01', '2024-12-31', 'SANCTIONED', ?)";
        jdbc.sql(project).params(id, 2024).update();
        sheet(faculty, id).andExpect(jsonPath("$.scores[5].score").value(0));
        jdbc.sql(project).params(id, 2026).update();   // the appraised year is 2025-26: 2025 and 2026 count
        sheet(faculty, id).andExpect(jsonPath("$.scores[5].score").value(50));
    }

    // ---- who sees it ----

    @Test
    void theHodSeesTheSameMarksOnceItIsSubmitted() throws Exception {
        jdbc.sql("INSERT INTO outreach (appraisal_id, role, event_activity, organization, event_date) VALUES (?, 'RESOURCE_PERSON', 'Talk', 'X', '2026-01-10')")
                .params(id).update();
        db.complete(id);
        appraisals.submit(id, faculty);
        sheet(hod, id).andExpect(status().isOk()).andExpect(jsonPath("$.scores[7].score").value(5.0));
        appraisals.transition(id, hod, WorkflowAction.Verb.START, null);
        appraisals.transition(id, hod, WorkflowAction.Verb.APPROVE, null);
        sheet(principal, id).andExpect(jsonPath("$.scores[7].score").value(5.0));
    }

    @Test
    void theMaximumIsASnapshotOfThePolicyTakenWhenTheAppraisalWasCreated() {
        Integer max = jdbc.sql("SELECT max_marks FROM appraisal_scores WHERE appraisal_id = ? AND criterion = 'TEACHING_LEARNING'")
                .param(id).query(Integer.class).single();
        assertEquals(40, max);
    }

    // ---- only what happened in the academic year (1 June 2025 to 31 May 2026) counts ----

    @Test
    void anEntryDatedOutsideTheAcademicYearEarnsNothingWhateverGotIntoTheDatabase() throws Exception {
        // inside the year: the first and last day, and the first and last month
        jdbc.sql("INSERT INTO outreach (appraisal_id, role, event_activity, organization, event_date) VALUES (?, 'RESOURCE_PERSON', 'A', 'X', '2025-06-01')").params(id).update();
        jdbc.sql("INSERT INTO outreach (appraisal_id, role, event_activity, organization, event_date) VALUES (?, 'RESOURCE_PERSON', 'B', 'X', '2026-05-31')").params(id).update();
        // outside it: the day before and the day after
        jdbc.sql("INSERT INTO outreach (appraisal_id, role, event_activity, organization, event_date) VALUES (?, 'RESOURCE_PERSON', 'C', 'X', '2025-05-31')").params(id).update();
        jdbc.sql("INSERT INTO outreach (appraisal_id, role, event_activity, organization, event_date) VALUES (?, 'RESOURCE_PERSON', 'D', 'X', '2026-06-01')").params(id).update();
        String book = "INSERT INTO books (appraisal_id, authors, title, publisher, month_year, type) VALUES (?, 'A', 'B', 'P', ?, 'BOOK')";
        jdbc.sql(book).params(id, "2025-06").update();    // first month: counts
        jdbc.sql(book).params(id, "2026-05").update();    // last month: counts
        jdbc.sql(book).params(id, "2025-05").update();    // the month before: does not
        jdbc.sql(book).params(id, "2026-06").update();    // the month after: does not
        jdbc.sql("INSERT INTO memberships_awards (appraisal_id, item, awarding_body, level, year) VALUES (?, 'Old', 'IEEE', 'INTL', 2024)").params(id).update();
        jdbc.sql("INSERT INTO memberships_awards (appraisal_id, item, awarding_body, level, year) VALUES (?, 'New', 'IEEE', 'INTL', 2026)").params(id).update();
        // a training programme counts only when it both starts and ends inside the year
        String fdp = "INSERT INTO fdps (appraisal_id, title, mode, institution_venue, start_date, end_date, days) VALUES (?, ?, 'ONLINE', 'V', ?, ?, 2)";
        jdbc.sql(fdp).params(id, "In", "2026-05-30", "2026-05-31").update();
        jdbc.sql(fdp).params(id, "Across", "2026-05-30", "2026-06-02").update();
        sheet(faculty, id)
                .andExpect(jsonPath("$.scores[7].breakdown[0].count").value(2))                  // outreach
                .andExpect(jsonPath("$.scores[6].breakdown[2].count").value(2))                  // books
                .andExpect(jsonPath("$.scores[8].breakdown[0].count").value(1))                  // memberships
                .andExpect(jsonPath("$.scores[2].breakdown[0].count").value(1));                 // programmes
    }
}
