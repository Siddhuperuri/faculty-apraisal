package edu.svec.fams;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * The Head of the Department can revoke an appraisal at any stage before the final approval, including one already forwarded
 * to the Principal, without anyone's consent; every revocation is audited; a final approval can never be revoked.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RevokeIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestDb db;
    @Autowired JdbcClient jdbc;

    FamsUserPrincipal faculty, hod, otherHod, principal;
    long id;
    String base;

    @BeforeEach
    void setUp() throws Exception {
        db.reset();
        faculty = db.faculty("f1@test.edu", "E001", "CSE", "ASST_PROF");
        hod = db.hod("hod.cse@test.edu", "CSE");
        otherHod = db.hod("hod.ece@test.edu", "ECE");
        principal = db.user("principal@test.edu", Role.PRINCIPAL);
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

    private String statusNow() { return jdbc.sql("SELECT status FROM appraisals WHERE id = ?").param(id).query(String.class).single(); }

    private void submit() throws Exception { call(faculty, "POST", base + "/submit", Map.of("declarationAccepted", true)).andExpect(status().isOk()); }
    private void start(FamsUserPrincipal who) throws Exception { call(who, "POST", base + "/review/start", null).andExpect(status().isOk()); }
    private void approve(FamsUserPrincipal who) throws Exception { call(who, "POST", base + "/review/approve", null).andExpect(status().isOk()); }

    private ResultActions revoke(FamsUserPrincipal who, String reason) throws Exception {
        return call(who, "POST", base + "/review/revoke", reason == null ? Map.of() : Map.of("reason", reason));
    }

    @Test
    void anAppraisalWithTheHodGoesBackToTheFacultyMemberAsADraft() throws Exception {
        submit();
        revoke(hod, "Missing course files").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DRAFT"));
        assertEquals("DRAFT", statusNow());
        call(faculty, "GET", base, null).andExpect(jsonPath("$.editable").value(true));

        submit();
        start(hod);
        revoke(hod, "Wrong academic year").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @Test
    void anAppraisalForwardedToThePrincipalComesBackToTheHodWithoutThePrincipalsConsent() throws Exception {
        submit();
        start(hod);
        approve(hod);                                   // HOD_APPROVED: forwarded, the Principal has not begun
        assertEquals("HOD_APPROVED", statusNow());
        revoke(hod, "Recommendation to be reconsidered").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("HOD_REVIEW"));
        assertEquals("HOD_REVIEW", statusNow());

        approve(hod);
        start(principal);                               // PRINCIPAL_REVIEW: the Principal is reading it
        assertEquals("PRINCIPAL_REVIEW", statusNow());
        revoke(hod, "Marks to be checked again").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("HOD_REVIEW"));
        // and the chain carries on from there
        approve(hod);
        start(principal);
        approve(principal);
        assertEquals("APPROVED", statusNow());
    }

    @Test
    void everyRevocationIsAuditedWithTheHodTheTimeTheReasonAndBothStatuses() throws Exception {
        submit();
        start(hod);
        approve(hod);
        revoke(hod, "Please re-check the research marks").andExpect(status().isOk());

        Map<String, Object> row = jdbc.sql("SELECT actor_id, entity_id, metadata, created_at FROM audit_logs WHERE action = 'APPRAISAL_REVOKED'")
                .query().singleRow();
        assertEquals(hod.id(), ((Number) row.get("actor_id")).longValue());
        assertEquals(id, ((Number) row.get("entity_id")).longValue());
        assertTrue(row.get("created_at") != null);
        JsonNode meta = json.readTree(row.get("metadata").toString());
        assertEquals(hod.id(), meta.get("hodId").asLong());
        assertEquals("Please re-check the research marks", meta.get("reason").asText());
        assertEquals("HOD_APPROVED", meta.get("fromStatus").asText());
        assertEquals("HOD_REVIEW", meta.get("toStatus").asText());
        assertTrue(meta.get("at").asText().length() > 10);

        // and the review history shows it
        Map<String, Object> history = jdbc.sql("SELECT actor_role, from_status, to_status, comment FROM review_actions WHERE action = 'HOD_REVOKE'")
                .query().singleRow();
        assertEquals("HOD", history.get("actor_role"));
        assertEquals("HOD_APPROVED", history.get("from_status"));
        assertEquals("HOD_REVIEW", history.get("to_status"));
        assertEquals("Please re-check the research marks", history.get("comment"));
    }

    @Test
    void aFinalApprovalCanNeverBeRevoked() throws Exception {
        submit();
        start(hod);
        approve(hod);
        start(principal);
        approve(principal);
        revoke(hod, "Changed my mind").andExpect(status().isConflict());
        assertEquals("APPROVED", statusNow());
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM audit_logs WHERE action = 'APPRAISAL_REVOKED'").query(Integer.class).single());
    }

    @Test
    void aReasonIsRequiredAndOnlyTheAssignedHodMayRevoke() throws Exception {
        submit();
        revoke(hod, null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.reason").exists());
        revoke(hod, "   ").andExpect(status().isBadRequest());
        revoke(hod, "x".repeat(2001)).andExpect(status().isBadRequest());

        revoke(otherHod, "Not my department").andExpect(status().isNotFound());   // another department's HoD cannot even see it
        revoke(faculty, "Withdraw").andExpect(status().isForbidden());
        revoke(principal, "Withdraw").andExpect(status().isForbidden());
        assertEquals("SUBMITTED", statusNow());
    }

    @Test
    void aDraftHasNothingToRevoke() throws Exception {
        revoke(hod, "Nothing sent yet").andExpect(status().is4xxClientError());
        assertEquals("DRAFT", statusNow());
    }
}
