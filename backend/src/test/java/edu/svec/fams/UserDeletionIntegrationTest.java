package edu.svec.fams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.svec.fams.admin.UserDeletionService;
import edu.svec.fams.appraisal.AppraisalService;
import edu.svec.fams.auth.FamsUserPrincipal;
import edu.svec.fams.auth.Role;
import edu.svec.fams.common.ApiException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * "Delete User Permanently": who may do it, what it needs, what goes, what is kept (anonymously) and what can no longer
 * reach anything afterwards.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserDeletionIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestDb db;
    @Autowired JdbcClient jdbc;
    @Autowired AppraisalService appraisals;
    @Autowired UserDeletionService deletion;

    FamsUserPrincipal admin, otherAdmin, faculty, bystander, hod;

    @BeforeEach
    void setUp() {
        db.reset();
        admin = db.user("admin@test.edu", Role.ADMIN);
        otherAdmin = db.user("admin2@test.edu", Role.ADMIN);
        faculty = db.faculty("f1@test.edu", "E001", "CSE", "ASST_PROF");
        bystander = db.faculty("f2@test.edu", "E002", "CSE", "PROFESSOR");
        hod = db.hod("hod@test.edu", "CSE");
    }

    private ResultActions preview(FamsUserPrincipal who, long target) throws Exception {
        return mvc.perform(get("/api/admin/users/" + target + "/deletion-preview").with(user(who)));
    }

    private ResultActions delete(FamsUserPrincipal who, long target, Object confirmEmail) throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        if (confirmEmail != null) body.put("confirmEmail", confirmEmail);
        return mvc.perform(post("/api/admin/users/" + target + "/delete-permanently").with(user(who)).with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private int count(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(Integer.class).single();
    }

    private void submit(FamsUserPrincipal who, long appraisalId) throws Exception {
        db.complete(appraisalId);
        mvc.perform(post("/api/appraisals/" + appraisalId + "/submit").with(user(who)).with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON).content("{\"declarationAccepted\":true}")).andExpect(status().isOk());
    }

    // ---- authorization and confirmation ----

    @Test
    void onlyAnAdministratorMayPreviewOrDelete() throws Exception {
        for (FamsUserPrincipal who : List.of(faculty, bystander, hod, db.user("principal@test.edu", Role.PRINCIPAL))) {
            preview(who, faculty.id()).andExpect(status().isForbidden());
            delete(who, bystander.id(), "f2@test.edu").andExpect(status().isForbidden());
        }
        mvc.perform(post("/api/admin/users/" + faculty.id() + "/delete-permanently").with(user(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"confirmEmail\":\"f1@test.edu\"}"))
                .andExpect(status().isForbidden());   // no CSRF token
        assertEquals(1, count("SELECT COUNT(*) FROM users WHERE id = ?", faculty.id()));
        assertEquals(1, count("SELECT COUNT(*) FROM users WHERE id = ?", bystander.id()));
    }

    @Test
    void theAccountsEmailAddressMustBeTypedAndNothingChangesWithoutIt() throws Exception {
        delete(admin, faculty.id(), null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.confirmEmail").exists());
        delete(admin, faculty.id(), "").andExpect(status().isBadRequest());
        delete(admin, faculty.id(), "DELETE").andExpect(status().isBadRequest());
        delete(admin, faculty.id(), "f2@test.edu").andExpect(status().isBadRequest());
        delete(admin, faculty.id(), 42).andExpect(status().isBadRequest());
        assertEquals(1, count("SELECT COUNT(*) FROM users WHERE id = ?", faculty.id()));
        assertEquals(1, count("SELECT COUNT(*) FROM faculty_profiles WHERE user_id = ?", faculty.id()));
        assertEquals(0, count("SELECT COUNT(*) FROM audit_logs WHERE action = 'USER_DELETED'"));

        // upper case and surrounding spaces do not matter; the address is what counts
        delete(admin, faculty.id(), "  F1@Test.edu ").andExpect(status().isOk());
    }

    @Test
    void anAdministratorCannotDeleteTheirOwnAccount() throws Exception {
        preview(admin, admin.id()).andExpect(status().isOk()).andExpect(jsonPath("$.canDelete").value(false))
                .andExpect(jsonPath("$.blockedReason").value("You cannot delete the account you are signed in with."));
        delete(admin, admin.id(), "admin@test.edu").andExpect(status().isConflict());
        assertEquals(1, count("SELECT COUNT(*) FROM users WHERE id = ?", admin.id()));
    }

    @Test
    void theOnlyActiveAdministratorCannotBeDeleted() {
        // the signed-in administrator is always another account, so the rule is checked with an actor that is not active
        FamsUserPrincipal ghost = new FamsUserPrincipal(999_999L, "ghost@test.edu", null, Role.ADMIN, true);
        jdbc.sql("UPDATE users SET status = 'DISABLED' WHERE id = ?").param(otherAdmin.id()).update();
        ApiException refused = assertThrows(ApiException.class,
                () -> deletion.delete(ghost, admin.id(), Map.of("confirmEmail", "admin@test.edu")));
        assertEquals(409, refused.status().value());
        assertTrue(refused.getMessage().contains("only active Administrator"));
        assertEquals(1, count("SELECT COUNT(*) FROM users WHERE id = ?", admin.id()));
        assertFalse(deletion.preview(ghost, admin.id()).canDelete());

        // a disabled administrator is not the last active one: it may go, and so may a spare active one
        assertEquals("REMOVED", deletion.delete(ghost, otherAdmin.id(), Map.of("confirmEmail", "admin2@test.edu")).outcome());
    }

    @Test
    void anotherAdministratorMayBeDeletedWhileOneStaysActive() throws Exception {
        delete(admin, otherAdmin.id(), "admin2@test.edu").andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("REMOVED"));
        assertEquals(0, count("SELECT COUNT(*) FROM users WHERE id = ?", otherAdmin.id()));
        assertEquals(1, count("SELECT COUNT(*) FROM users WHERE id = ? AND status = 'ACTIVE'", admin.id()));
    }

    @Test
    void anUnknownOrAlreadyDeletedAccountIsNotFound() throws Exception {
        delete(admin, 987_654_321L, "x@test.edu").andExpect(status().isNotFound());
        preview(admin, 987_654_321L).andExpect(status().isNotFound());
        delete(admin, bystander.id(), "f2@test.edu").andExpect(status().isOk());
        delete(admin, bystander.id(), "f2@test.edu").andExpect(status().isNotFound());
    }

    // ---- a faculty member who never submitted ----

    @Test
    void aFacultyMemberWithOnlyDraftsIsRemovedWithEverythingTheyEntered() throws Exception {
        long draft = appraisals.create(faculty);
        jdbc.sql("INSERT INTO fdps (appraisal_id, title, mode, institution_venue, days) VALUES (?, 'F', 'ONLINE', 'V', 3)").param(draft).update();
        jdbc.sql("INSERT INTO password_reset_tokens (user_id, token_hash, expires_at) VALUES (?, REPEAT('a', 64), DATE_ADD(NOW(), INTERVAL 1 HOUR))")
                .param(faculty.id()).update();
        long bystanderAppraisal = appraisals.create(bystander);

        preview(admin, faculty.id()).andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("f1@test.edu")).andExpect(jsonPath("$.role").value("FACULTY"))
                .andExpect(jsonPath("$.canDelete").value(true)).andExpect(jsonPath("$.appraisalsRemoved").value(1))
                .andExpect(jsonPath("$.appraisalsKept").value(0)).andExpect(jsonPath("$.keepsAnonymousRecord").value(false));

        delete(admin, faculty.id(), "f1@test.edu").andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("REMOVED")).andExpect(jsonPath("$.appraisalsRemoved").value(1));

        assertEquals(0, count("SELECT COUNT(*) FROM users WHERE id = ?", faculty.id()));
        assertEquals(0, count("SELECT COUNT(*) FROM faculty_profiles WHERE user_id = ?", faculty.id()));
        assertEquals(0, count("SELECT COUNT(*) FROM appraisals WHERE id = ?", draft));
        assertEquals(0, count("SELECT COUNT(*) FROM fdps WHERE appraisal_id = ?", draft));
        assertEquals(0, count("SELECT COUNT(*) FROM general_information WHERE appraisal_id = ?", draft));
        assertEquals(0, count("SELECT COUNT(*) FROM appraisal_scores WHERE appraisal_id = ?", draft));
        assertEquals(0, count("SELECT COUNT(*) FROM password_reset_tokens WHERE user_id = ?", faculty.id()));

        // nobody else, and nothing else, was touched
        assertEquals(1, count("SELECT COUNT(*) FROM appraisals WHERE id = ?", bystanderAppraisal));
        assertEquals(1, count("SELECT COUNT(*) FROM users WHERE id = ?", bystander.id()));
        assertEquals(1, count("SELECT COUNT(*) FROM departments WHERE code = 'CSE'"));
        assertEquals(1, count("SELECT COUNT(*) FROM hod_assignments WHERE user_id = ?", hod.id()));

        // the audit entry names the account by number and role, and carries neither its address nor its name
        JsonNode entry = json.readTree(jdbc.sql("SELECT CAST(metadata AS CHAR) FROM audit_logs WHERE action = 'USER_DELETED'")
                .query(String.class).single());
        assertEquals("FACULTY", entry.get("role").asText());
        assertFalse(entry.toString().contains("f1@test.edu") || entry.toString().contains("E001"));
        assertEquals(faculty.id(), jdbc.sql("SELECT entity_id FROM audit_logs WHERE action = 'USER_DELETED'").query(Long.class).single());
        assertEquals(admin.id(), jdbc.sql("SELECT actor_id FROM audit_logs WHERE action = 'USER_DELETED'").query(Long.class).single());
    }

    @Test
    void afterwardsTheAccountCannotSignInAndItsSessionEnds() throws Exception {
        long mine = appraisals.create(faculty);
        mvc.perform(get("/api/appraisals/" + mine).with(user(faculty))).andExpect(status().isOk());
        delete(admin, faculty.id(), "f1@test.edu").andExpect(status().isOk());
        // the session of the signed-in person ends at their next request
        mvc.perform(get("/api/appraisals").with(user(faculty))).andExpect(status().isUnauthorized());
        // and the address and password no longer open anything
        mvc.perform(post("/api/auth/login").with(csrf().asHeader()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"f1@test.edu\",\"password\":\"" + TestDb.PASSWORD + "\"}")).andExpect(status().isUnauthorized());
    }

    // ---- a faculty member whose appraisal has been through review ----

    @Test
    void anAppraisalThatWasReviewedIsKeptAnonymouslyAndReachesNobody() throws Exception {
        long submitted = appraisals.create(faculty);
        jdbc.sql("UPDATE general_information SET research_ids = 'ORCID 0000-0001' WHERE appraisal_id = ?").param(submitted).update();
        submit(faculty, submitted);
        jdbc.sql("UPDATE faculty_profiles SET qualification = 'Ph.D.', orcid = '0000-0001-2345-6789', contact_no = '9876543210' WHERE user_id = ?")
                .param(faculty.id()).update();
        mvc.perform(post("/api/appraisals/" + submitted + "/review/start").with(user(hod)).with(csrf().asHeader())).andExpect(status().isOk());
        int steps = count("SELECT COUNT(*) FROM review_actions WHERE appraisal_id = ?", submitted);

        preview(admin, faculty.id()).andExpect(jsonPath("$.appraisalsKept").value(1)).andExpect(jsonPath("$.appraisalsRemoved").value(0))
                .andExpect(jsonPath("$.keepsAnonymousRecord").value(true));
        delete(admin, faculty.id(), "f1@test.edu").andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("ANONYMIZED")).andExpect(jsonPath("$.appraisalsKept").value(1));

        // the account is a stub: no address, no name, no password, no sign-in, marked deleted
        Map<String, Object> stub = jdbc.sql("SELECT email, name, employee_id, contact_no, password_hash, status, deleted_at FROM users WHERE id = ?")
                .param(faculty.id()).query().singleRow();
        assertEquals("deleted-user-" + faculty.id() + "@deleted.invalid", stub.get("email"));
        assertEquals("Deleted user", stub.get("name"));
        assertNull(stub.get("employee_id"));
        assertNull(stub.get("contact_no"));
        assertNull(stub.get("password_hash"));
        assertEquals("DISABLED", stub.get("status"));
        assertNotNull(stub.get("deleted_at"));

        // the faculty record and the appraisal keep what the audit needs, without the person
        Map<String, Object> profile = jdbc.sql("SELECT name, employee_id, contact_no, qualification, orcid FROM faculty_profiles WHERE user_id = ?")
                .param(faculty.id()).query().singleRow();
        assertEquals("Deleted user", profile.get("name"));
        assertEquals("DELETED-" + faculty.id(), profile.get("employee_id"));
        assertNull(profile.get("contact_no"));
        assertNull(profile.get("qualification"));
        assertNull(profile.get("orcid"));
        assertEquals(1, count("SELECT COUNT(*) FROM appraisals WHERE id = ?", submitted));
        assertEquals(steps, count("SELECT COUNT(*) FROM review_actions WHERE appraisal_id = ?", submitted));
        assertNull(jdbc.sql("SELECT contact_no FROM general_information WHERE appraisal_id = ?").param(submitted).query((rs, n) -> rs.getString(1)).list().get(0));
        assertNull(jdbc.sql("SELECT research_ids FROM general_information WHERE appraisal_id = ?").param(submitted).query((rs, n) -> rs.getString(1)).list().get(0));

        // it is out of every view: the head of the department cannot open it or list it
        mvc.perform(get("/api/appraisals/" + submitted).with(user(hod))).andExpect(status().isNotFound());
        mvc.perform(get("/api/appraisals").with(user(hod))).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/appraisals/" + submitted + "/report.pdf").with(user(hod))).andExpect(status().isNotFound());

        // out of the administrator's lists, detail and counts
        JsonNode users = json.readTree(mvc.perform(get("/api/admin/users").with(user(admin))).andReturn().getResponse().getContentAsString());
        for (JsonNode u : users) assertFalse(u.get("email").asText().contains("deleted-user") || u.get("id").asLong() == faculty.id());
        mvc.perform(get("/api/admin/users/" + faculty.id()).with(user(admin))).andExpect(status().isNotFound());
        mvc.perform(get("/api/admin/users").param("q", "deleted").with(user(admin))).andExpect(jsonPath("$.length()").value(0));
        JsonNode analytics = json.readTree(mvc.perform(get("/api/admin/analytics").with(user(admin))).andReturn().getResponse().getContentAsString());
        int facultyAccounts = 0;
        for (JsonNode role : analytics.get("users")) {
            if ("FACULTY".equals(role.get("role").asText())) facultyAccounts = role.get("active").asInt() + role.get("disabled").asInt();
        }
        assertEquals(1, facultyAccounts, "only the bystander is counted");
        mvc.perform(get("/api/admin/overview").with(user(admin))).andExpect(status().isOk());
        assertEquals(0, count("SELECT COUNT(*) FROM users WHERE deleted_at IS NOT NULL AND status = 'ACTIVE'"));

        // the person's address and employee ID are free again
        Map<String, Object> again = Map.of("email", "f1@test.edu", "role", "FACULTY", "name", "Dr. New", "employeeId", "E001",
                "departmentId", jdbc.sql("SELECT id FROM departments WHERE code = 'CSE'").query(Long.class).single(),
                "cadreId", jdbc.sql("SELECT id FROM cadres WHERE code = 'ASST_PROF'").query(Long.class).single());
        mvc.perform(post("/api/admin/users").with(user(admin)).with(csrf().asHeader()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(again))).andExpect(status().isCreated());
    }

    // ---- a head of department ----

    @Test
    void aHeadOfDepartmentIsUnassignedAndTheDepartmentsLeftWithoutOneAreSaid() throws Exception {
        FamsUserPrincipal second = db.hod("hod2@test.edu", "CSE");
        long me = jdbc.sql("SELECT id FROM departments WHERE code = 'ME'").query(Long.class).single();
        jdbc.sql("INSERT INTO hod_assignments (user_id, department_id) VALUES (?, ?)").params(hod.id(), me).update();

        // CSE has another head; ME has only this one
        preview(admin, hod.id()).andExpect(status().isOk())
                .andExpect(jsonPath("$.departmentsUnassigned.length()").value(2))
                .andExpect(jsonPath("$.departmentsLeftWithoutHead.length()").value(1))
                .andExpect(jsonPath("$.departmentsLeftWithoutHead[0]").value(org.hamcrest.Matchers.startsWith("ME")));

        delete(admin, hod.id(), "hod@test.edu").andExpect(status().isOk()).andExpect(jsonPath("$.departmentsUnassigned").value(2));
        assertEquals(0, count("SELECT COUNT(*) FROM hod_assignments WHERE user_id = ?", hod.id()));
        assertEquals(1, count("SELECT COUNT(*) FROM hod_assignments WHERE user_id = ?", second.id()));
        assertEquals(2, count("SELECT COUNT(*) FROM departments WHERE code IN ('CSE', 'ME')"));
    }

    @Test
    void aHeadWhoReviewedLeavesAnAnonymousStubBecauseTheHistoryPointsAtIt() throws Exception {
        long mine = appraisals.create(faculty);
        submit(faculty, mine);
        mvc.perform(post("/api/appraisals/" + mine + "/review/start").with(user(hod)).with(csrf().asHeader())).andExpect(status().isOk());
        mvc.perform(post("/api/appraisals/" + mine + "/review/approve").with(user(hod)).with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"Good\"}")).andExpect(status().isOk());

        delete(admin, hod.id(), "hod@test.edu").andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("ANONYMIZED"))
                .andExpect(jsonPath("$.appraisalsKept").value(0));
        assertEquals(1, count("SELECT COUNT(*) FROM users WHERE id = ? AND deleted_at IS NOT NULL AND password_hash IS NULL", hod.id()));
        assertEquals(0, count("SELECT COUNT(*) FROM hod_assignments WHERE user_id = ?", hod.id()));
        // the history is intact and still names the role of the person who acted
        assertEquals(3, count("SELECT COUNT(*) FROM review_actions WHERE appraisal_id = ?", mine));
        assertEquals(2, count("SELECT COUNT(*) FROM review_actions WHERE actor_id = ?", hod.id()));
        // and the reviewer's own access is gone at once
        mvc.perform(get("/api/appraisals").with(user(hod))).andExpect(status().isUnauthorized());
    }

    // ---- the schema is accounted for ----

    @Test
    void everyColumnThatPointsAtAnAccountIsHandledByTheDeletion() {
        Set<String> found = new HashSet<>(jdbc.sql("""
                SELECT CONCAT(table_name, '.', column_name) FROM information_schema.key_column_usage
                WHERE table_schema = DATABASE() AND referenced_table_name = 'users'""").query(String.class).list());
        assertEquals(new HashSet<>(UserDeletionService.REFERENCES), found,
                "a table now points at users: say in UserDeletionService what becomes of its rows");
    }
}
