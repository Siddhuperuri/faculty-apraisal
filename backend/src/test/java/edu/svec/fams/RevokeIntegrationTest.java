package edu.svec.fams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.svec.fams.auth.FamsUserPrincipal;
import edu.svec.fams.auth.Role;
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

/**
 * An appraisal can be revoked at any stage before the final approval, and always goes one level back, with the reviewer's
 * remarks: the Head of the Department's revoke returns it to the faculty member; the Principal's or the Director
 * Technical's returns it to the Head of the Department. Each reader sees only the remarks that are theirs.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RevokeIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestDb db;
    @Autowired JdbcClient jdbc;

    FamsUserPrincipal faculty, hod, otherHod, principal, director, admin;
    long id;
    String base;

    @BeforeEach
    void setUp() throws Exception {
        db.reset();
        faculty = db.faculty("f1@test.edu", "E001", "CSE", "ASST_PROF");
        hod = db.hod("hod.cse@test.edu", "CSE");
        otherHod = db.hod("hod.ece@test.edu", "ECE");
        principal = db.user("principal@test.edu", Role.PRINCIPAL);
        director = db.user("director@test.edu", Role.DIRECTOR);
        admin = db.user("admin@test.edu", Role.ADMIN);
        String created = call(faculty, "POST", "/api/appraisals", null).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        id = Long.parseLong(created.replaceAll("\\D+", ""));
        db.complete(id);
        base = "/api/appraisals/" + id;
    }

    private ResultActions call(FamsUserPrincipal who, String method, String path, Object body) throws Exception {
        var req = method.equals("GET") ? get(path) : post(path);
        req = req.with(user(who)).with(csrf().asHeader());
        if (body != null) req = req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return mvc.perform(req);
    }

    private JsonNode view(FamsUserPrincipal who) throws Exception {
        return json.readTree(call(who, "GET", base, null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private String statusNow() { return jdbc.sql("SELECT status FROM appraisals WHERE id = ?").param(id).query(String.class).single(); }

    private void submit() throws Exception { call(faculty, "POST", base + "/submit", Map.of("declarationAccepted", true)).andExpect(status().isOk()); }
    private void start(FamsUserPrincipal who) throws Exception { call(who, "POST", base + "/review/start", null).andExpect(status().isOk()); }
    private void approve(FamsUserPrincipal who) throws Exception { call(who, "POST", base + "/review/approve", null).andExpect(status().isOk()); }
    private void approve(FamsUserPrincipal who, String comment) throws Exception {
        call(who, "POST", base + "/review/approve", Map.of("comment", comment)).andExpect(status().isOk());
    }

    private ResultActions revoke(FamsUserPrincipal who, String reason) throws Exception {
        return call(who, "POST", base + "/review/revoke", reason == null ? Map.of() : Map.of("reason", reason));
    }

    // ---- the HoD's revoke goes to the faculty member ----

    @Test
    void theHodsRevokeReturnsItToTheFacultyMemberAtEveryStageBeforeFinalApproval() throws Exception {
        submit();
        revoke(hod, "Missing course files").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DRAFT"));
        assertEquals("DRAFT", statusNow());
        call(faculty, "GET", base, null).andExpect(jsonPath("$.editable").value(true));

        submit();
        start(hod);
        revoke(hod, "Wrong academic year").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DRAFT"));

        submit();
        start(hod);
        approve(hod);                                   // forwarded: HOD_APPROVED
        revoke(hod, "Recommendation to be reconsidered").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DRAFT"));

        submit();
        start(hod);
        approve(hod);
        start(principal);                               // PRINCIPAL_REVIEW
        revoke(hod, "Marks to be checked again").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DRAFT"));
        assertEquals("DRAFT", statusNow());
    }

    @Test
    void theFacultyMemberSeesThatItWasRevokedAndTheHodsRemarks() throws Exception {
        submit();
        revoke(hod, "Please attach the course files").andExpect(status().isOk());

        JsonNode v = view(faculty);
        assertEquals("DRAFT", v.get("status").asText());
        assertEquals("HOD", v.get("returnedNotice").get("byRole").asText());
        assertEquals("Please attach the course files", v.get("returnedNotice").get("remarks").asText());
        assertEquals(1, v.get("revocations").size());
        assertFalse(v.has("history"), "the faculty member is never sent the review history");
        call(faculty, "GET", "/api/appraisals", null).andExpect(jsonPath("$[0].revoked").value(true));

        // once it is sent again the notice goes, and the HoD's earlier remarks stay listed
        submit();
        JsonNode again = view(faculty);
        assertFalse(again.has("returnedNotice"));
        assertEquals(1, again.get("revocations").size());
        call(faculty, "GET", "/api/appraisals", null).andExpect(jsonPath("$[0].revoked").value(false));
    }

    // ---- the Principal's and the Director Technical's revoke goes to the HoD ----

    @Test
    void thePrincipalsRevokeReturnsItToTheHodWithTheRemarksNotToTheFacultyMember() throws Exception {
        submit();
        start(hod);
        approve(hod, "Recommended");
        revoke(principal, "The research marks need another look").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("HOD_REVIEW"));
        assertEquals("HOD_REVIEW", statusNow());

        JsonNode forHod = view(hod);
        assertEquals("PRINCIPAL", forHod.get("returnedNotice").get("byRole").asText());
        assertEquals("The research marks need another look", forHod.get("returnedNotice").get("remarks").asText());

        JsonNode forFaculty = view(faculty);
        assertFalse(forFaculty.has("returnedNotice"), "the faculty member is not shown the Principal's remarks");
        assertEquals(0, forFaculty.get("revocations").size());
        assertEquals("HOD_REVIEW", forFaculty.get("status").asText());
        assertFalse(forFaculty.has("history"));

        // and the chain carries on from there: the HoD forwards it again and the Principal approves
        approve(hod);
        start(principal);
        approve(principal);
        assertEquals("APPROVED", statusNow());
    }

    @Test
    void theDirectorTechnicalCanRevokeAnAppraisalThePrincipalHasBegun() throws Exception {
        submit();
        start(hod);
        approve(hod);
        start(principal);
        revoke(director, "Not ready").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("HOD_REVIEW"));
        Map<String, Object> row = jdbc.sql("SELECT actor_role, action, from_status, to_status, comment FROM review_actions WHERE action = 'DIRECTOR_REVOKE'")
                .query().singleRow();
        assertEquals("DIRECTOR", row.get("actor_role"));
        assertEquals("PRINCIPAL_REVIEW", row.get("from_status"));
        assertEquals("HOD_REVIEW", row.get("to_status"));
        assertEquals("Not ready", row.get("comment"));
    }

    @Test
    void thePrincipalCannotRevokeWhatTheHodHasNotForwardedAndNothingIsFinalOnceApproved() throws Exception {
        submit();
        revoke(principal, "Too early").andExpect(status().isNotFound());       // not visible to the Principal yet
        start(hod);
        approve(hod);
        start(principal);
        approve(principal);
        revoke(principal, "Changed my mind").andExpect(status().isConflict());
        revoke(hod, "Changed my mind").andExpect(status().isConflict());
        assertEquals("APPROVED", statusNow());
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM audit_logs WHERE action = 'APPRAISAL_REVOKED'").query(Integer.class).single());
    }

    // ---- who sees which remarks ----

    @Test
    void theHodsRevokeRemarksAreNotShownToThePrincipalOrTheDirector() throws Exception {
        submit();
        start(hod);
        approve(hod, "Recommended for approval");
        revoke(hod, "Withdrawn: confidential reason").andExpect(status().isOk());      // back to the faculty member
        submit();
        start(hod);
        approve(hod, "Recommended again");

        for (FamsUserPrincipal who : new FamsUserPrincipal[] {principal, director}) {
            String body = call(who, "GET", base, null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertFalse(body.contains("Withdrawn: confidential reason"), "the HoD's revoke remarks are for the faculty member");
            assertTrue(body.contains("Recommended again"), "the HoD's recommendation is for the Principal");
            assertFalse(json.readTree(body).has("returnedNotice"));
        }
    }

    @Test
    void thePrincipalsApprovalRemarksAreNotShownToTheHod() throws Exception {
        submit();
        start(hod);
        approve(hod, "Recommended");
        start(principal);
        approve(principal, "Excellent work, confidential note");

        String body = call(hod, "GET", base, null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("Excellent work, confidential note"));
        assertTrue(body.contains("Recommended"));
    }

    // ---- the audit trail ----

    @Test
    void everyRevocationIsAuditedWithTheReviewerTheTimeTheReasonAndBothStatuses() throws Exception {
        submit();
        start(hod);
        approve(hod);
        revoke(principal, "Please re-check the research marks").andExpect(status().isOk());

        Map<String, Object> row = jdbc.sql("SELECT actor_id, entity_id, metadata, created_at FROM audit_logs WHERE action = 'APPRAISAL_REVOKED'")
                .query().singleRow();
        assertEquals(principal.id(), ((Number) row.get("actor_id")).longValue());
        assertEquals(id, ((Number) row.get("entity_id")).longValue());
        assertTrue(row.get("created_at") != null);
        JsonNode meta = json.readTree(row.get("metadata").toString());
        assertEquals("PRINCIPAL", meta.get("revokedBy").asText());
        assertEquals(principal.id(), meta.get("revokerId").asLong());
        assertEquals("Please re-check the research marks", meta.get("reason").asText());
        assertEquals("HOD_APPROVED", meta.get("fromStatus").asText());
        assertEquals("HOD_REVIEW", meta.get("toStatus").asText());
        assertTrue(meta.get("at").asText().length() > 10);

        revoke(hod, "Returned to the faculty member").andExpect(status().isOk());
        Map<String, Object> history = jdbc.sql("SELECT actor_role, from_status, to_status, comment FROM review_actions WHERE action = 'HOD_REVOKE'")
                .query().singleRow();
        assertEquals("HOD", history.get("actor_role"));
        assertEquals("HOD_REVIEW", history.get("from_status"));
        assertEquals("DRAFT", history.get("to_status"));
    }

    @Test
    void theHodSeesTheWholeRevokeAndResubmitTrailEvenWhileTheAppraisalIsADraftAgain() throws Exception {
        submit();
        start(hod);
        approve(hod);
        revoke(principal, "First: the principal's remarks").andExpect(status().isOk());      // back to the HoD
        revoke(hod, "Second: the HoD's remarks").andExpect(status().isOk());                 // back to the faculty member
        submit();                                                                          // sent again
        assertEquals("SUBMITTED", statusNow());
        revoke(hod, "Third").andExpect(status().isOk());

        // the HoD cannot open a draft, but the trail is theirs to read
        call(hod, "GET", base, null).andExpect(status().isNotFound());
        call(hod, "GET", base + "/revocations", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.timesRevoked").value(3))
                .andExpect(jsonPath("$.timesResent").value(1))
                .andExpect(jsonPath("$.entries.length()").value(5))
                .andExpect(jsonPath("$.entries[1].action").value("PRINCIPAL_REVOKE"))
                .andExpect(jsonPath("$.entries[1].remarks").value("First: the principal's remarks"));

        // the roster counts them too, and marks the appraisal as revoked
        String console = call(hod, "GET", "/api/hod/console", null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode row = json.readTree(console).get("roster").get(0);
        assertEquals(3, row.get("timesRevoked").asInt());
        assertEquals(1, row.get("timesResent").asInt());
        assertTrue(row.get("revoked").asBoolean());
        assertEquals(id, row.get("trailId").asLong());
        assertTrue(row.get("appraisalId").isNull(), "a draft's content is still not opened by the HoD");
    }

    @Test
    void onlyTheDepartmentsHodSeesTheTrail() throws Exception {
        submit();
        call(otherHod, "GET", base + "/revocations", null).andExpect(status().isNotFound());
        call(faculty, "GET", base + "/revocations", null).andExpect(status().isForbidden());
        call(principal, "GET", base + "/revocations", null).andExpect(status().isForbidden());
        call(admin, "GET", base + "/revocations", null).andExpect(status().is4xxClientError());
    }

    // ---- who may revoke, and with what ----

    @Test
    void remarksAreRequiredAndOnlyTheAssignedReviewerMayRevoke() throws Exception {
        submit();
        revoke(hod, null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.reason").exists());
        revoke(hod, "   ").andExpect(status().isBadRequest());
        revoke(hod, "x".repeat(2001)).andExpect(status().isBadRequest());

        revoke(otherHod, "Not my department").andExpect(status().isNotFound());   // another department's HoD cannot even see it
        revoke(faculty, "Withdraw").andExpect(status().isForbidden());
        revoke(admin, "Withdraw").andExpect(status().isForbidden());
        assertEquals("SUBMITTED", statusNow());
    }

    @Test
    void aDraftHasNothingToRevoke() throws Exception {
        revoke(hod, "Nothing sent yet").andExpect(status().is4xxClientError());
        assertEquals("DRAFT", statusNow());
    }
}
