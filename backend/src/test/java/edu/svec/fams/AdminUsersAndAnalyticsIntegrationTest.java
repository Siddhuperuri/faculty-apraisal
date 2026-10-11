package edu.svec.fams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.svec.fams.appraisal.AppraisalService;
import edu.svec.fams.auth.FamsUserPrincipal;
import edu.svec.fams.auth.Role;
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

/** The administrator's dashboard figures (aggregates only), the users list filters, and giving a user a different role. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminUsersAndAnalyticsIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestDb db;
    @Autowired JdbcClient jdbc;
    @Autowired AppraisalService appraisals;

    FamsUserPrincipal admin, cse1, cse2, ece1, hodCse;

    @BeforeEach
    void setUp() {
        db.reset();
        admin = db.user("admin@test.edu", Role.ADMIN);
        cse1 = db.faculty("anita.cse@test.edu", "E001", "CSE", "ASST_PROF");
        cse2 = db.faculty("bala.cse@test.edu", "E002", "CSE", "LECTURER");
        ece1 = db.faculty("chitra.ece@test.edu", "E003", "ECE", "PROFESSOR");
        hodCse = db.hod("hod.cse@test.edu", "CSE");
    }

    private ResultActions as(FamsUserPrincipal who, String method, String path, Object body) throws Exception {
        var req = method.equals("PUT") ? put(path) : get(path);
        req = req.with(user(who)).with(csrf().asHeader());
        if (body != null) req = req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return mvc.perform(req);
    }

    private JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    // ---- analytics ----

    @Test
    void theDashboardShowsEachDepartmentSeparatelyWithCountsOnly() throws Exception {
        long a1 = appraisals.create(cse1);
        long a2 = appraisals.create(cse2);
        appraisals.create(ece1);
        jdbc.sql("UPDATE appraisals SET status = 'SUBMITTED' WHERE id = ?").param(a1).update();
        jdbc.sql("UPDATE appraisals SET status = 'HOD_REVIEW' WHERE id = ?").param(a2).update();

        String text = as(admin, "GET", "/api/admin/analytics", null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode data = json.readTree(text);
        JsonNode cse = null;
        JsonNode ece = null;
        for (JsonNode d : data.get("departments")) {
            if (d.get("code").asText().equals("CSE")) cse = d;
            if (d.get("code").asText().equals("ECE")) ece = d;
        }
        assertEquals(2, cse.get("faculty").asInt());
        assertEquals(1, cse.get("headsOfDepartment").asInt());
        assertEquals(2, cse.get("started").asInt());
        assertEquals(2, cse.get("submitted").asInt());
        assertEquals(0, cse.get("approved").asInt());
        assertEquals(1, cse.get("byStatus").get("SUBMITTED").asInt());
        assertEquals(1, cse.get("byStatus").get("HOD_REVIEW").asInt());
        assertEquals(100.0, cse.get("submissionRate").asDouble());
        assertEquals(1, ece.get("faculty").asInt());
        assertEquals(1, ece.get("started").asInt());
        assertEquals(0, ece.get("submitted").asInt());       // it is a draft

        JsonNode totals = data.get("totals");
        assertEquals(3, totals.get("faculty").asInt());
        assertEquals(3, totals.get("started").asInt());
        assertEquals(2, totals.get("submitted").asInt());
        assertEquals(66.7, totals.get("submissionRate").asDouble());
        assertEquals(1, data.get("byStatus").get("DRAFT").asInt());

        // aggregated only: no name, e-mail address or employee ID appears anywhere in it
        for (String secret : List.of("anita", "bala", "chitra", "@test.edu", "E001", "E002", "E003")) {
            assertFalse(text.contains(secret), "the analytics must not carry " + secret);
        }
    }

    @Test
    void theYearCanBeChosenAndOnlyAnAdministratorMayReadTheFigures() throws Exception {
        long year = jdbc.sql("SELECT id FROM academic_years WHERE name = '2025-26'").query(Long.class).single();
        as(admin, "GET", "/api/admin/analytics?academicYearId=" + year, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.year.name").value("2025-26"));
        as(admin, "GET", "/api/admin/analytics?academicYearId=999999", null).andExpect(status().isNotFound());
        as(cse1, "GET", "/api/admin/analytics", null).andExpect(status().isForbidden());
        as(hodCse, "GET", "/api/admin/analytics", null).andExpect(status().isForbidden());
    }

    // ---- users ----

    @Test
    void theUsersListCanBeNarrowedByStatusAndDepartment() throws Exception {
        jdbc.sql("UPDATE users SET status = 'DISABLED' WHERE id = ?").param(cse2.id()).update();
        JsonNode disabled = body(as(admin, "GET", "/api/admin/users?status=DISABLED", null).andExpect(status().isOk()));
        assertEquals(1, disabled.size());
        assertEquals("bala.cse@test.edu", disabled.get(0).get("email").asText());

        long cse = jdbc.sql("SELECT id FROM departments WHERE code = 'CSE'").query(Long.class).single();
        JsonNode inCse = body(as(admin, "GET", "/api/admin/users?departmentId=" + cse, null));
        List<String> emails = new java.util.ArrayList<>();
        inCse.forEach(u -> emails.add(u.get("email").asText()));
        assertTrue(emails.containsAll(List.of("anita.cse@test.edu", "bala.cse@test.edu", "hod.cse@test.edu")), emails.toString());
        assertFalse(emails.contains("chitra.ece@test.edu"));

        as(admin, "GET", "/api/admin/users?status=WHATEVER", null).andExpect(status().isBadRequest());
        JsonNode both = body(as(admin, "GET", "/api/admin/users?status=ACTIVE&role=HOD&departmentId=" + cse, null));
        assertEquals(1, both.size());
    }

    // ---- role changes ----

    @Test
    void aUserCanBeGivenTheAdministratorRoleAndTheirSessionsEnd() throws Exception {
        int before = jdbc.sql("SELECT session_version FROM users WHERE id = ?").param(hodCse.id()).query(Integer.class).single();
        as(admin, "PUT", "/api/admin/users/" + hodCse.id() + "/role", Map.of("role", "ADMIN")).andExpect(status().isOk())
                .andExpect(jsonPath("$.row.role").value("ADMIN"));
        assertEquals("ADMIN", jdbc.sql("SELECT role FROM users WHERE id = ?").param(hodCse.id()).query(String.class).single());
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM hod_assignments WHERE user_id = ?").param(hodCse.id()).query(Integer.class).single());
        assertEquals(before + 1, jdbc.sql("SELECT session_version FROM users WHERE id = ?").param(hodCse.id()).query(Integer.class).single());

        Map<String, Object> audit = jdbc.sql("SELECT actor_id, entity_id, metadata FROM audit_logs WHERE action = 'USER_ROLE_CHANGED'").query().singleRow();
        assertEquals(admin.id(), ((Number) audit.get("actor_id")).longValue());
        JsonNode meta = json.readTree(audit.get("metadata").toString());
        assertEquals("HOD", meta.get("fromRole").asText());
        assertEquals("ADMIN", meta.get("toRole").asText());
    }

    @Test
    void aHeadOfDepartmentNeedsTheirDepartmentsAndTheRulesAreEnforced() throws Exception {
        long ece = jdbc.sql("SELECT id FROM departments WHERE code = 'ECE'").query(Long.class).single();
        as(admin, "PUT", "/api/admin/users/" + cse2.id() + "/role", Map.of("role", "HOD")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.hodDepartmentIds").exists());
        as(admin, "PUT", "/api/admin/users/" + cse2.id() + "/role", Map.of("role", "HOD", "hodDepartmentIds", List.of(ece)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.row.role").value("HOD"));
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM hod_assignments WHERE user_id = ? AND department_id = ?").params(cse2.id(), ece).query(Integer.class).single());

        as(admin, "PUT", "/api/admin/users/" + admin.id() + "/role", Map.of("role", "PRINCIPAL")).andExpect(status().isConflict());   // not your own
        as(admin, "PUT", "/api/admin/users/" + cse1.id() + "/role", Map.of("role", "FACULTY")).andExpect(status().isConflict());     // already
        as(admin, "PUT", "/api/admin/users/" + cse1.id() + "/role", Map.of("role", "SUPERUSER")).andExpect(status().isBadRequest());
        as(admin, "PUT", "/api/admin/users/" + hodCse.id() + "/role", Map.of("role", "FACULTY")).andExpect(status().isBadRequest());   // no faculty record
        jdbc.sql("UPDATE users SET status = 'DISABLED' WHERE id = ?").param(cse1.id()).update();
        as(admin, "PUT", "/api/admin/users/" + cse1.id() + "/role", Map.of("role", "ADMIN")).andExpect(status().isConflict());   // only an active account
        as(hodCse, "PUT", "/api/admin/users/" + cse1.id() + "/role", Map.of("role", "ADMIN")).andExpect(status().isForbidden());
    }

    @Test
    void changingAHodsDepartmentsIsAuditedWithBeforeAndAfter() throws Exception {
        long ece = jdbc.sql("SELECT id FROM departments WHERE code = 'ECE'").query(Long.class).single();
        as(admin, "PUT", "/api/admin/users/" + hodCse.id(), Map.of("hodDepartmentIds", List.of(ece))).andExpect(status().isOk());
        Map<String, Object> row = jdbc.sql("SELECT metadata FROM audit_logs WHERE action = 'USER_UPDATED' ORDER BY id DESC LIMIT 1").query().singleRow();
        JsonNode meta = json.readTree(row.get("metadata").toString());
        assertEquals(1, meta.get("hodDepartmentIdsBefore").size());
        assertEquals(ece, meta.get("hodDepartmentIdsAfter").get(0).asLong());
    }
}
