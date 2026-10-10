package edu.svec.fams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import edu.svec.fams.appraisal.AppraisalService;
import edu.svec.fams.auth.FamsUserPrincipal;
import edu.svec.fams.auth.Role;
import edu.svec.fams.common.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
class WorkflowIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired TestDb db;
    @Autowired JdbcClient jdbc;
    @Autowired AppraisalService service;

    static final String DECLARATION = "{\"declarationAccepted\":true}";

    FamsUserPrincipal faculty, otherFaculty, hod, otherDeptHod, principal, admin;

    @BeforeEach
    void setUp() {
        db.reset();
        faculty = db.faculty("f1@test.edu", "E001", "CSE", "ASST_PROF");
        otherFaculty = db.faculty("f2@test.edu", "E002", "CSE", "PROFESSOR");
        hod = db.hod("hod@test.edu", "CSE");
        otherDeptHod = db.hod("hod2@test.edu", "ECE");
        principal = db.user("p@test.edu", Role.PRINCIPAL);
        admin = db.user("admin@test.edu", Role.ADMIN);
    }

    private ResultActions call(FamsUserPrincipal who, String method, String path, String body) throws Exception {
        var req = method.equals("GET") ? get(path) : post(path);
        req = req.with(user(who)).with(csrf().asHeader());
        if (body != null) req = req.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(req);
    }

    private long createAppraisal(FamsUserPrincipal who) throws Exception {
        String json = call(who, "POST", "/api/appraisals", null)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = Long.parseLong(json.replaceAll("\\D+", ""));
        db.complete(id);
        return id;
    }

    private void submitted(long id) throws Exception {
        call(faculty, "POST", "/api/appraisals/" + id + "/submit", DECLARATION).andExpect(status().isOk());
    }

    // ---- creation & policy snapshot ----

    @Test
    void creatingSnapshotsTheCadresMaxMarksForTheCriteriaThatHaveOne() throws Exception {
        long id = createAppraisal(faculty);
        Integer total = jdbc.sql("SELECT SUM(max_marks) FROM appraisal_scores WHERE appraisal_id = ?")
                .param(id).query(Integer.class).single();
        assertEquals(85, total); // Assistant Professor: B1 40, B2 15, B3 15, B4 15; B5 to B9 are marked per entry
        call(faculty, "GET", "/api/appraisals/" + id, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.editable").value(true))
                .andExpect(jsonPath("$.cadre").value("Assistant Professor"))
                .andExpect(jsonPath("$.employeeId").value("E001"))
                .andExpect(jsonPath("$.email").value("f1@test.edu"))
                .andExpect(jsonPath("$.facultyName").value("Dr. E001"))
                .andExpect(jsonPath("$.scores.length()").value(9));
    }

    @Test
    void teachingMarksDifferByCadreWhileB2B3AndB4DoNot() throws Exception {
        long prof = createAppraisal(otherFaculty); // PROFESSOR: teaching 30; B2, B3 and B4 are 15 for every cadre
        long asst = createAppraisal(faculty);      // ASST_PROF: teaching 40
        String sql = "SELECT max_marks FROM appraisal_scores WHERE appraisal_id = ? AND criterion = ?";
        assertEquals(30, jdbc.sql(sql).params(prof, "TEACHING_LEARNING").query(Integer.class).single());
        assertEquals(40, jdbc.sql(sql).params(asst, "TEACHING_LEARNING").query(Integer.class).single());
        for (String criterion : List.of("STUDENT_MENTORING", "FDP_CERTIFICATIONS", "ADMINISTRATIVE")) {
            assertEquals(15, jdbc.sql(sql).params(prof, criterion).query(Integer.class).single(), criterion);
            assertEquals(15, jdbc.sql(sql).params(asst, criterion).query(Integer.class).single(), criterion);
        }
    }

    @Test
    void newPolicyVersionAppliesToNewAppraisalsOnlyAndHistoryIsPreserved() throws Exception {
        long before = createAppraisal(faculty);

        // Institution publishes a further policy version (4, after the document's 1, V24's 2 and the current policy's 3) for the Professor cadre with different marks.
        long yearId = jdbc.sql("SELECT id FROM academic_years WHERE name = '2025-26'").query(Long.class).single();
        long cadreId = jdbc.sql("SELECT id FROM cadres WHERE code = 'PROFESSOR'").query(Long.class).single();
        jdbc.sql("INSERT INTO scoring_policies (academic_year_id, cadre_id, version) VALUES (?,?,4)")
                .params(yearId, cadreId).update();
        long v2 = jdbc.sql("SELECT id FROM scoring_policies WHERE cadre_id = ? AND version = 4")
                .param(cadreId).query(Long.class).single();
        jdbc.sql("INSERT INTO scoring_policy_criteria (policy_id, criterion, max_marks) VALUES (?, 'TEACHING_LEARNING', 100)")
                .param(v2).update();

        long after = createAppraisal(otherFaculty);
        Integer teachingNew = jdbc.sql("SELECT max_marks FROM appraisal_scores WHERE appraisal_id = ? AND criterion = 'TEACHING_LEARNING'")
                .param(after).query(Integer.class).single();
        assertEquals(100, teachingNew);                       // new appraisal uses the new version

        Integer teachingOld = jdbc.sql("SELECT max_marks FROM appraisal_scores WHERE appraisal_id = ? AND criterion = 'TEACHING_LEARNING'")
                .param(before).query(Integer.class).single();
        assertEquals(40, teachingOld);                        // earlier appraisal unchanged (Asst Prof v3)
    }

    @Test
    void secondAppraisalForSameYearIsRejected() throws Exception {
        createAppraisal(faculty);
        call(faculty, "POST", "/api/appraisals", null).andExpect(status().isConflict());
    }

    @Test
    void facultyWithoutAProfileCannotStartAnAppraisal() throws Exception {
        FamsUserPrincipal noProfile = db.user("np@test.edu", Role.FACULTY);
        call(noProfile, "POST", "/api/appraisals", null).andExpect(status().isConflict());
    }

    @Test
    void onlyFacultyCanStartAnAppraisal() throws Exception {
        call(hod, "POST", "/api/appraisals", null).andExpect(status().isForbidden());
        call(principal, "POST", "/api/appraisals", null).andExpect(status().isForbidden());
        call(admin, "POST", "/api/appraisals", null).andExpect(status().isForbidden());
    }

    // ---- visibility / ownership ----

    @Test
    void otherFacultyCannotSeeOrSubmitSomeoneElsesAppraisal() throws Exception {
        long id = createAppraisal(faculty);
        call(otherFaculty, "GET", "/api/appraisals/" + id, null).andExpect(status().isNotFound());
        call(otherFaculty, "POST", "/api/appraisals/" + id + "/submit", DECLARATION).andExpect(status().isNotFound());
    }

    @Test
    void hodCannotSeeADraft() throws Exception {
        long id = createAppraisal(faculty);
        call(hod, "GET", "/api/appraisals/" + id, null).andExpect(status().isNotFound());
        call(hod, "POST", "/api/appraisals/" + id + "/review/start", null).andExpect(status().isNotFound());
        submitted(id);
        call(hod, "GET", "/api/appraisals/" + id, null).andExpect(status().isOk());
    }

    @Test
    void hodOfAnotherDepartmentCannotSeeIt() throws Exception {
        long id = createAppraisal(faculty);
        submitted(id);
        call(otherDeptHod, "GET", "/api/appraisals/" + id, null).andExpect(status().isNotFound());
        call(otherDeptHod, "POST", "/api/appraisals/" + id + "/review/start", null).andExpect(status().isNotFound());
    }

    @Test
    void principalCannotSeeAppraisalBeforeHodApproval() throws Exception {
        long id = createAppraisal(faculty);
        submitted(id);
        call(principal, "GET", "/api/appraisals/" + id, null).andExpect(status().isNotFound());
    }

    @Test
    void adminCannotReadOrActOnAppraisalContent() throws Exception {
        long id = createAppraisal(faculty);
        submitted(id);
        call(admin, "GET", "/api/appraisals/" + id, null).andExpect(status().isNotFound());
        call(admin, "POST", "/api/appraisals/" + id + "/review/approve", null).andExpect(status().isNotFound());
    }

    @Test
    void nonexistentAndMalformedIdsAreClientErrors() throws Exception {
        call(faculty, "GET", "/api/appraisals/999999", null).andExpect(status().isNotFound());
        call(faculty, "GET", "/api/appraisals/abc", null).andExpect(status().isBadRequest());
    }

    @Test
    void stateChangingCallsWithoutCsrfAreRejected() throws Exception {
        long id = createAppraisal(faculty);
        mvc.perform(post("/api/appraisals/" + id + "/submit").with(user(faculty))).andExpect(status().isForbidden());
    }

    // ---- workflow rules ----

    @Test
    void facultyCannotApproveOwnAppraisal() throws Exception {
        long id = createAppraisal(faculty);
        call(faculty, "POST", "/api/appraisals/" + id + "/review/approve", null).andExpect(status().isForbidden());
        call(faculty, "POST", "/api/appraisals/" + id + "/review/start", null).andExpect(status().isForbidden());
    }

    @Test
    void hodCannotSkipStraightToApprovalFromSubmitted() throws Exception {
        long id = createAppraisal(faculty);
        submitted(id);
        call(hod, "POST", "/api/appraisals/" + id + "/review/approve", null).andExpect(status().isConflict());
    }

    @Test
    void principalCannotActBeforeHodApproval() throws Exception {
        long id = createAppraisal(faculty);
        submitted(id);
        call(principal, "POST", "/api/appraisals/" + id + "/review/start", null).andExpect(status().isNotFound());
    }

    @Test
    void doubleSubmitIsRejected() throws Exception {
        long id = createAppraisal(faculty);
        submitted(id);
        call(faculty, "POST", "/api/appraisals/" + id + "/submit", DECLARATION).andExpect(status().isConflict());
    }

    @Test
    void overlongCommentIsRejectedNotA500() throws Exception {
        long id = createAppraisal(faculty);
        submitted(id);
        call(hod, "POST", "/api/appraisals/" + id + "/review/start", null).andExpect(status().isOk());
        call(hod, "POST", "/api/appraisals/" + id + "/review/approve",
                "{\"comment\":\"" + "x".repeat(2001) + "\"}").andExpect(status().isBadRequest());
        call(hod, "GET", "/api/appraisals/" + id, null).andExpect(jsonPath("$.status").value("HOD_REVIEW"));
    }

    @Test
    void fullLifecycleFromDraftToFinalApproval() throws Exception {
        long id = createAppraisal(faculty);
        String base = "/api/appraisals/" + id;

        call(faculty, "POST", base + "/submit", DECLARATION).andExpect(jsonPath("$.status").value("SUBMITTED"));
        call(faculty, "GET", base, null).andExpect(jsonPath("$.editable").value(false));

        call(hod, "POST", base + "/review/start", null).andExpect(jsonPath("$.status").value("HOD_REVIEW"));
        // the HoD's comment is optional; when given it is kept with the approval
        call(hod, "POST", base + "/review/approve", "{\"comment\":\"Attach FDP certificates next year\"}")
                .andExpect(jsonPath("$.status").value("HOD_APPROVED"));
        call(faculty, "GET", base, null)
                .andExpect(jsonPath("$.editable").value(false))
                .andExpect(jsonPath("$.history[-1:].action").value("HOD_APPROVE"))
                .andExpect(jsonPath("$.history[2].comment").value(org.hamcrest.Matchers.nullValue()));   // remarks are not for the faculty member
        call(hod, "GET", base, null).andExpect(jsonPath("$.history[2].comment").value("Attach FDP certificates next year"));

        call(principal, "POST", base + "/review/start", null)
                .andExpect(jsonPath("$.status").value("PRINCIPAL_REVIEW"));
        call(principal, "POST", base + "/review/approve", null).andExpect(jsonPath("$.status").value("APPROVED"));

        // Approved is final: no further action possible and faculty cannot edit.
        call(faculty, "POST", base + "/submit", DECLARATION).andExpect(status().isConflict());
        call(hod, "POST", base + "/review/approve", null).andExpect(status().isConflict());
        call(faculty, "GET", base, null)
                .andExpect(jsonPath("$.editable").value(false))
                .andExpect(jsonPath("$.finalApprovedAt").isNotEmpty())
                .andExpect(jsonPath("$.history.length()").value(5));

        Integer audits = jdbc.sql("SELECT COUNT(*) FROM audit_logs WHERE entity_id = ?")
                .param(id).query(Integer.class).single();
        assertEquals(6, audits); // 1 create + 5 transitions (submit, then start and approve at each of the two levels)
    }

    // ---- the form's Declaration ----

    @Test
    void submissionRequiresTheDeclaration() throws Exception {
        long id = createAppraisal(faculty);
        String url = "/api/appraisals/" + id + "/submit";
        call(faculty, "POST", url, "{\"declarationAccepted\":false}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.declarationAccepted").value("You must accept the declaration to submit."));
        call(faculty, "POST", url, "{}").andExpect(status().isBadRequest());
        call(faculty, "GET", "/api/appraisals/" + id, null).andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.declaredAt").doesNotExist());
    }

    @Test
    void anAppraisalCannotBeSubmittedUntilTheEssentialsAreFilledInAndTheViewSaysWhatIsMissing() throws Exception {
        String created = call(faculty, "POST", "/api/appraisals", null).andReturn().getResponse().getContentAsString();
        long id = Long.parseLong(created.replaceAll("\\D+", ""));
        String base = "/api/appraisals/" + id;
        jdbc.sql("UPDATE general_information SET contact_no = NULL, qualification_specialization = NULL, "
                + "joining_date_institution = NULL, joining_date_designation = NULL WHERE appraisal_id = ?").param(id).update();

        // everything is missing, and the owner is told exactly what
        call(faculty, "GET", base, null).andExpect(jsonPath("$.submitBlockers.length()").value(1))
                .andExpect(jsonPath("$.submitBlockers[0]").value(org.hamcrest.Matchers.startsWith("General Information")));
        call(faculty, "POST", base + "/submit", DECLARATION).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("Not ready to submit. Still needed: General Information")));
        call(faculty, "GET", base, null).andExpect(jsonPath("$.status").value("DRAFT"));

        // the details are filled in: nothing is missing (no number of courses and no typed score is required any more)
        jdbc.sql("UPDATE general_information SET contact_no = '9000000000', qualification_specialization = 'M.Tech', "
                + "joining_date_institution = '2015-06-01', joining_date_designation = '2020-06-01' WHERE appraisal_id = ?").param(id).update();
        call(faculty, "GET", base, null).andExpect(jsonPath("$.submitBlockers.length()").value(0));
        call(faculty, "POST", base + "/submit", DECLARATION).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUBMITTED"));
        // once submitted nothing is listed
        call(faculty, "GET", base, null).andExpect(jsonPath("$.submitBlockers.length()").value(0));
    }

    @Test
    void theDeclarationIsRecordedWithTheSubmission() throws Exception {
        long id = createAppraisal(faculty);
        String base = "/api/appraisals/" + id;
        call(faculty, "POST", base + "/submit", "{\"declarationAccepted\":true}").andExpect(status().isOk());
        call(faculty, "GET", base, null)
                .andExpect(jsonPath("$.declarationPlace").doesNotExist())
                .andExpect(jsonPath("$.declaredAt").isNotEmpty());
    }

    @Test
    void aRejectedSubmissionDoesNotChangeTheDeclarationDate() throws Exception {
        long id = createAppraisal(faculty);
        submitted(id);
        // A second submit is refused (already SUBMITTED) and must not touch the first declaration.
        Object first = jdbc.sql("SELECT declared_at FROM appraisals WHERE id = ?").param(id).query().singleRow().get("declared_at");
        call(faculty, "POST", "/api/appraisals/" + id + "/submit", "{\"declarationAccepted\":true}").andExpect(status().isConflict());
        assertEquals(first, jdbc.sql("SELECT declared_at FROM appraisals WHERE id = ?").param(id).query().singleRow().get("declared_at"));
    }

    @Test
    void onlyFacultyCanSubmit() throws Exception {
        long id = createAppraisal(faculty);
        call(hod, "POST", "/api/appraisals/" + id + "/submit", DECLARATION).andExpect(status().isForbidden());
        call(principal, "POST", "/api/appraisals/" + id + "/submit", DECLARATION).andExpect(status().isForbidden());
    }

    // ---- integrity under concurrency ----

    @Test
    void concurrentSubmitsOfTheSameAppraisalSucceedExactlyOnce() throws Exception {
        long id = createAppraisal(faculty);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Callable<Boolean> task = () -> {
                go.await();
                try {
                    service.submit(id, faculty);
                    return true;
                } catch (ApiException e) {
                    assertEquals(409, e.status().value());
                    return false;
                }
            };
            results.add(pool.submit(task));
        }
        go.countDown();
        int successes = 0;
        for (Future<Boolean> f : results) {
            if (f.get()) successes++;
        }
        pool.shutdown();

        assertEquals(1, successes, "exactly one concurrent submit may win");
        Integer rows = jdbc.sql("SELECT COUNT(*) FROM review_actions WHERE appraisal_id = ?")
                .param(id).query(Integer.class).single();
        assertEquals(1, rows, "and it must be recorded exactly once");
    }
}
