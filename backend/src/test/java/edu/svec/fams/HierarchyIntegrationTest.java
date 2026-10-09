package edu.svec.fams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import edu.svec.fams.auth.FamsUserPrincipal;
import edu.svec.fams.auth.Role;
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
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** The approval chain faculty -> HoD -> Principal, through the HTTP API: who may act, who may see, and what no longer exists. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HierarchyIntegrationTest {

    static final String DECLARATION = "{\"declarationAccepted\":true}";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestDb db;
    @Autowired JdbcClient jdbc;

    FamsUserPrincipal faculty, hodCse, hodEce, principal, director, admin;
    long id;
    String base;

    @BeforeEach
    void setUp() throws Exception {
        db.reset();
        faculty = db.faculty("f1@test.edu", "E001", "CSE", "ASST_PROF");
        hodCse = db.hod("hod.cse@test.edu", "CSE");
        hodEce = db.hod("hod.ece@test.edu", "ECE");
        principal = db.user("principal@test.edu", Role.PRINCIPAL);
        director = db.user("director@test.edu", Role.DIRECTOR);
        admin = db.user("admin@test.edu", Role.ADMIN);

        String created = call(faculty, "POST", "/api/appraisals", null).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        id = Long.parseLong(created.replaceAll("\\D+", ""));
        db.complete(id);
        base = "/api/appraisals/" + id;
    }

    private ResultActions call(FamsUserPrincipal who, String method, String path, String body) throws Exception {
        var req = method.equals("GET") ? get(path) : post(path);
        req = req.with(user(who)).with(csrf().asHeader());
        if (body != null) req = req.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(req);
    }

    /** The text of the report PDF as this user is given it. */
    private String reportText(FamsUserPrincipal who, String appraisalPath) throws Exception {
        byte[] pdf = call(who, "GET", appraisalPath + "/report.pdf", null).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        PdfReader reader = new PdfReader(pdf);
        try {
            PdfTextExtractor text = new PdfTextExtractor(reader);
            StringBuilder all = new StringBuilder();
            for (int p = 1; p <= reader.getNumberOfPages(); p++) all.append(text.getTextFromPage(p)).append('\n');
            return all.toString();
        } finally {
            reader.close();
        }
    }

    private void start(FamsUserPrincipal who) throws Exception { call(who, "POST", base + "/review/start", null).andExpect(status().isOk()); }
    private void approve(FamsUserPrincipal who) throws Exception { call(who, "POST", base + "/review/approve", null).andExpect(status().isOk()); }
    private void review(FamsUserPrincipal who) throws Exception { start(who); approve(who); }

    private String statusNow() {
        return jdbc.sql("SELECT status FROM appraisals WHERE id = ?").param(id).query(String.class).single();
    }

    private void canSee(FamsUserPrincipal who, boolean yes) throws Exception {
        call(who, "GET", base, null).andExpect(yes ? status().isOk() : status().isNotFound());
    }

    private void submit() throws Exception { call(faculty, "POST", base + "/submit", DECLARATION).andExpect(status().isOk()); }

    // ---- the chain ----

    @Test
    void facultySubmitToTheirHodWhoForwardsToThePrincipalAndNoOneElseCanMoveItAlong() throws Exception {
        submit();
        assertEquals("SUBMITTED", statusNow());
        // it is with the HoD of the faculty member's own department, and nobody else
        call(principal, "POST", base + "/review/start", null).andExpect(status().isNotFound());
        call(hodEce, "POST", base + "/review/start", null).andExpect(status().isNotFound());
        call(hodCse, "POST", base + "/review/approve", null).andExpect(status().isConflict());       // must begin the review first
        start(hodCse);
        assertEquals("HOD_REVIEW", statusNow());
        call(principal, "POST", base + "/review/start", null).andExpect(status().isNotFound());

        // the HoD comments, approves and so forwards it
        call(hodCse, "POST", base + "/review/approve", "{\"comment\":\"Recommended for the year.\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("HOD_APPROVED"));
        call(hodCse, "POST", base + "/review/approve", null).andExpect(status().isConflict());       // already done
        call(hodCse, "POST", base + "/review/start", null).andExpect(status().isConflict());

        start(principal);
        assertEquals("PRINCIPAL_REVIEW", statusNow());
        approve(principal);
        assertEquals("APPROVED", statusNow());

        List<String> trail = jdbc.sql("SELECT CONCAT(actor_role, ':', action) FROM review_actions WHERE appraisal_id = ? ORDER BY id")
                .param(id).query(String.class).list();
        assertEquals(List.of("FACULTY:SUBMIT", "HOD:START_HOD_REVIEW", "HOD:HOD_APPROVE",
                "PRINCIPAL:START_PRINCIPAL_REVIEW", "PRINCIPAL:PRINCIPAL_APPROVE"), trail);
        // the faculty member sees the steps, never the remarks; the Principal still sees them
        call(faculty, "GET", base, null).andExpect(jsonPath("$.history[2].action").value("HOD_APPROVE"))
                .andExpect(jsonPath("$.history[2].comment").value(org.hamcrest.Matchers.nullValue()));
        call(principal, "GET", base, null).andExpect(jsonPath("$.history[2].comment").value("Recommended for the year."));
        // the same on paper: the faculty member's copy leaves the remarks out, the official copy keeps them
        assertFalse(reportText(faculty, base).contains("Recommended for the year."));
        assertTrue(reportText(principal, base).contains("Recommended for the year."));
        assertFalse(reportText(faculty, base).contains("Recommended for the year."));   // and a stored official copy does not change that

        // approved is final for everyone
        call(faculty, "POST", base + "/submit", DECLARATION).andExpect(status().isConflict());
        for (FamsUserPrincipal r : List.of(hodCse, principal)) {
            call(r, "POST", base + "/review/start", null).andExpect(status().isConflict());
            call(r, "POST", base + "/review/approve", null).andExpect(status().isConflict());
        }
    }

    @Test
    void whoCanSeeTheAppraisalGrowsAsItMovesUp() throws Exception {
        canSee(hodCse, false);                                   // a draft is private
        submit();
        canSee(hodCse, true);
        canSee(principal, false);
        canSee(hodEce, false);                                   // not their department
        start(hodCse);
        canSee(principal, false);
        approve(hodCse);
        canSee(principal, true);
        review(principal);
        for (FamsUserPrincipal r : List.of(faculty, hodCse, principal)) canSee(r, true);
        canSee(hodEce, false);
        canSee(admin, false);                                    // administrators never read appraisals
    }

    @Test
    void facultyAndAdministratorsHaveNoReviewSteps() throws Exception {
        submit();
        for (String step : List.of("start", "approve")) {
            call(faculty, "POST", base + "/review/" + step, null).andExpect(status().isForbidden());
            call(admin, "POST", base + "/review/" + step, null).andExpect(status().isNotFound());
        }
    }

    // ---- the Director Technical, at the Principal's level ----

    private List<String> trail() {
        return jdbc.sql("SELECT CONCAT(actor_role, ':', action) FROM review_actions WHERE appraisal_id = ? ORDER BY id")
                .param(id).query(String.class).list();
    }

    @Test
    void theDirectorTechnicalTakesUpWhatTheHodForwardedAndTheirApprovalIsFinal() throws Exception {
        submit();
        // like the Principal, the Director cannot see or touch it until the HoD has forwarded it
        call(director, "POST", base + "/review/start", null).andExpect(status().isNotFound());
        canSee(director, false);
        start(hodCse);
        canSee(director, false);
        call(hodCse, "POST", base + "/review/approve", "{\"comment\":\"Recommended for the year.\"}").andExpect(status().isOk());
        canSee(director, true);

        call(director, "POST", base + "/review/approve", null).andExpect(status().isConflict());      // must begin the review first
        start(director);
        assertEquals("PRINCIPAL_REVIEW", statusNow());
        call(director, "POST", base + "/review/approve", "{\"comment\":\"Cleared by the Director.\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));

        assertEquals(List.of("FACULTY:SUBMIT", "HOD:START_HOD_REVIEW", "HOD:HOD_APPROVE",
                "DIRECTOR:START_DIRECTOR_REVIEW", "DIRECTOR:DIRECTOR_APPROVE"), trail());
        // the record says who it was, and the Director sees the whole history with its remarks; the faculty member only the steps
        call(director, "GET", base, null).andExpect(jsonPath("$.history[4].actorRole").value("DIRECTOR"))
                .andExpect(jsonPath("$.history[4].comment").value("Cleared by the Director."));
        call(faculty, "GET", base, null).andExpect(jsonPath("$.history[4].comment").value(org.hamcrest.Matchers.nullValue()));

        // final for everyone, the Principal included
        for (FamsUserPrincipal r : List.of(director, principal)) {
            call(r, "POST", base + "/review/start", null).andExpect(status().isConflict());
            call(r, "POST", base + "/review/approve", null).andExpect(status().isConflict());
        }

        // on paper: the Director's box carries the remarks, the Principal's is still on the form, unused; the faculty copy has no remarks
        String official = reportText(director, base);
        assertTrue(official.contains("Remarks of the Director Technical"));
        assertTrue(official.contains("Cleared by the Director."));
        assertTrue(official.contains("Remarks of the Principal"));
        assertFalse(reportText(faculty, base).contains("Cleared by the Director."));
    }

    @Test
    void thePrincipalAndTheDirectorAreInterchangeableAtThatLevel() throws Exception {
        submit();
        review(hodCse);

        // whoever begins, the other may not begin it again, but either may finish it
        start(principal);
        call(director, "POST", base + "/review/start", null).andExpect(status().isConflict());
        call(director, "POST", base + "/review/approve", "{\"comment\":\"Finished by the Director.\"}").andExpect(status().isOk());
        assertEquals("APPROVED", statusNow());
        assertEquals(List.of("FACULTY:SUBMIT", "HOD:START_HOD_REVIEW", "HOD:HOD_APPROVE",
                "PRINCIPAL:START_PRINCIPAL_REVIEW", "DIRECTOR:DIRECTOR_APPROVE"), trail());
        assertTrue(reportText(principal, base).contains("Finished by the Director."));
    }

    @Test
    void theDirectorTechnicalsBoxIsOnEveryReportAndFilledOnlyWhenTheyApproved() throws Exception {
        // not yet approved: the form already has the box
        submit();
        assertTrue(reportText(faculty, base).contains("Remarks of the Director Technical"));

        // approved by the Principal: the box is still printed, with nothing in it
        review(hodCse);
        start(principal);
        call(principal, "POST", base + "/review/approve", "{\"comment\":\"Noted by the Principal.\"}").andExpect(status().isOk());
        String text = reportText(principal, base);
        assertTrue(text.contains("Remarks of the Principal"));
        assertTrue(text.contains("Noted by the Principal."));
        assertTrue(text.contains("Remarks of the Director Technical"));
    }

    @Test
    void theDirectorTechnicalListsOnlyWhatTheyMayOpen() throws Exception {
        submit();
        call(director, "GET", "/api/appraisals", null).andExpect(jsonPath("$.length()").value(0));
        review(hodCse);
        call(director, "GET", "/api/appraisals", null).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value((int) id));
    }

    @Test
    void theDirectorTechnicalsAreaIsTheirsAloneAndTheyHaveNoOtherArea() throws Exception {
        call(director, "GET", "/api/director/console", null).andExpect(status().isOk());
        for (FamsUserPrincipal who : List.of(faculty, hodCse, principal, admin)) {
            call(who, "GET", "/api/director/console", null).andExpect(status().isForbidden());
        }
        for (String path : List.of("/api/principal/console", "/api/hod/console", "/api/admin/users")) {
            call(director, "GET", path, null).andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/director/console")).andExpect(status().isUnauthorized());
        // and, like the Principal, they cannot submit, or edit anything
        call(director, "POST", base + "/submit", DECLARATION).andExpect(status().isForbidden());
    }

    // ---- nothing goes back ----

    @Test
    void thereIsNoReturnStepForAnyoneAtAnyStage() throws Exception {
        String reason = "{\"comment\":\"Please correct this\"}";
        submit();
        start(hodCse);
        for (FamsUserPrincipal who : List.of(hodCse, principal, faculty, admin)) {
            call(who, "POST", base + "/review/return", reason).andExpect(status().isNotFound());
        }
        assertEquals("HOD_REVIEW", statusNow());
        approve(hodCse);
        start(principal);
        for (FamsUserPrincipal who : List.of(hodCse, principal)) {
            call(who, "POST", base + "/review/return", reason).andExpect(status().isNotFound());
        }
        assertEquals("PRINCIPAL_REVIEW", statusNow());

        // once submitted the faculty member can neither edit nor take it back
        call(faculty, "GET", base, null).andExpect(jsonPath("$.editable").value(false));
        call(faculty, "POST", base + "/submit", DECLARATION).andExpect(status().isConflict());
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM review_actions WHERE appraisal_id = ? AND action LIKE '%RETURN%'")
                .param(id).query(Integer.class).single());
    }

    @Test
    void theDatabaseItselfRefusesTheWithdrawnStatuses() {
        for (String old : List.of("HOD_RETURNED", "DEAN_REVIEW", "DEAN_RETURNED", "DEAN_APPROVED", "VP_REVIEW", "VP_RETURNED",
                "VP_APPROVED", "PRINCIPAL_RETURNED")) {
            assertThrows(DataAccessException.class,
                    () -> jdbc.sql("UPDATE appraisals SET status = ? WHERE id = ?").params(old, id).update(), old);
        }
    }

    // ---- the withdrawn Dean and Vice Principal levels ----

    @Test
    void theDeanAndVicePrincipalAreasAreGoneForEveryone() throws Exception {
        for (String path : List.of("/api/dean/console", "/api/vice-principal/console")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            for (FamsUserPrincipal who : List.of(faculty, hodCse, principal, admin)) {
                call(who, "GET", path, null).andExpect(status().isNotFound());
            }
        }
    }

    @Test
    void aWithdrawnAccountCannotSignInOrBeReopenedAndNoNewOneCanBeMade() throws Exception {
        TestHttp http = new TestHttp(mvc, json);
        long dean = db.withdrawnAccount("dean@test.edu", "DEAN");
        long vp = db.withdrawnAccount("vp@test.edu", "VICE_PRINCIPAL");

        // the right password, and still the same answer as any wrong one
        assertEquals(401, http.tryLogin("dean@test.edu", TestDb.PASSWORD).getResponse().getStatus());
        assertEquals(401, http.tryLogin("vp@test.edu", TestDb.PASSWORD).getResponse().getStatus());

        // the database will not have an active account of either role, old or new
        for (long account : List.of(dean, vp)) {
            assertThrows(DataAccessException.class, () -> jdbc.sql("UPDATE users SET status = 'ACTIVE' WHERE id = ?").param(account).update());
        }
        assertThrows(DataAccessException.class, () -> jdbc.sql("INSERT INTO users (email, role) VALUES ('d2@test.edu', 'DEAN')").update());
        assertThrows(DataAccessException.class, () -> jdbc.sql("INSERT INTO users (email, role) VALUES ('v2@test.edu', 'VICE_PRINCIPAL')").update());

        // nor will the administrator's screens: no such role to create, and the old accounts cannot be changed
        var session = http.login("admin@test.edu");
        for (String role : List.of("DEAN", "VICE_PRINCIPAL")) {
            http.post(session, "/api/admin/users", java.util.Map.of("email", "new." + role.toLowerCase() + "@test.edu", "role", role))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.role").value("Choose a role."));
        }
        http.put(session, "/api/admin/users/" + dean, java.util.Map.of("status", "ACTIVE")).andExpect(status().isConflict());
        http.post(session, "/api/admin/users/" + vp + "/reset-password", null).andExpect(status().isConflict());
        // they stay listed, for the record
        http.get(session, "/api/admin/users/" + dean).andExpect(status().isOk())
                .andExpect(jsonPath("$.row.role").value("DEAN")).andExpect(jsonPath("$.row.status").value("DISABLED"));
    }

    // ---- races ----

    @Test
    void twoHodsOfTheSameDepartmentStartingAtOnceGetExactlyOneWinner() throws Exception {
        FamsUserPrincipal second = db.hod("hod.cse2@test.edu", "CSE");
        submit();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (FamsUserPrincipal hod : List.of(hodCse, second)) {
            Callable<Integer> task = () -> {
                go.await();
                return call(hod, "POST", base + "/review/start", null).andReturn().getResponse().getStatus();
            };
            results.add(pool.submit(task));
        }
        go.countDown();
        List<Integer> codes = new ArrayList<>();
        for (Future<Integer> f : results) codes.add(f.get());
        pool.shutdown();
        assertEquals(1, codes.stream().filter(c -> c == 200).count(), "codes were " + codes);
        assertEquals(1, codes.stream().filter(c -> c == 409).count(), "codes were " + codes);
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM review_actions WHERE appraisal_id = ? AND action = 'START_HOD_REVIEW'")
                .param(id).query(Integer.class).single());
    }

    // ---- listing ----

    @Test
    void eachRolesListHoldsOnlyWhatTheyMayOpen() throws Exception {
        FamsUserPrincipal ece = db.faculty("f2@test.edu", "E002", "ECE", "ASST_PROF");
        long other = Long.parseLong(call(ece, "POST", "/api/appraisals", null).andReturn().getResponse().getContentAsString().replaceAll("\\D+", ""));
        db.complete(other);
        String otherBase = "/api/appraisals/" + other;
        submit();
        call(ece, "POST", otherBase + "/submit", DECLARATION).andExpect(status().isOk());

        // each HoD lists only their own department's; the Principal nothing yet
        call(hodCse, "GET", "/api/appraisals", null).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value((int) id));
        call(hodEce, "GET", "/api/appraisals", null).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value((int) other));
        call(principal, "GET", "/api/appraisals", null).andExpect(jsonPath("$.length()").value(0));
        call(admin, "GET", "/api/appraisals", null).andExpect(status().isForbidden());

        // the Principal (who covers the college) lists each one as its HoD forwards it
        review(hodCse);
        call(principal, "GET", "/api/appraisals", null).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value((int) id));
        call(hodEce, "POST", otherBase + "/review/start", null).andExpect(status().isOk());
        call(hodEce, "POST", otherBase + "/review/approve", null).andExpect(status().isOk());
        call(principal, "GET", "/api/appraisals", null).andExpect(jsonPath("$.length()").value(2));
    }
}
