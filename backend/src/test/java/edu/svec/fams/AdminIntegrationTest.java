package edu.svec.fams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.svec.fams.auth.Role;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestDb db;
    @Autowired JdbcClient jdbc;
    @Autowired edu.svec.fams.audit.AuditService audit;

    TestHttp http;
    MockHttpSession admin;
    long cse, ece, asstProf, professor;

    @BeforeEach
    void setUp() throws Exception {
        db.reset();
        http = new TestHttp(mvc, json);
        db.user("admin@test.edu", Role.ADMIN);
        admin = http.login("admin@test.edu");
        cse = id("departments", "CSE");
        ece = id("departments", "ECE");
        asstProf = id("cadres", "ASST_PROF");
        professor = id("cadres", "PROFESSOR");
    }

    private long id(String table, String code) {
        return jdbc.sql("SELECT id FROM " + table + " WHERE code = ?").param(code).query(Long.class).single();
    }

    private Map<String, Object> faculty(String email, String employeeId) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("role", "FACULTY");
        b.put("email", email);
        b.put("name", "Dr. Test Person");
        b.put("employeeId", employeeId);
        b.put("departmentId", cse);
        b.put("cadreId", asstProf);
        return b;
    }

    /** Every new or reset account starts with the college's standard password. */
    private String createdPassword(JsonNode created) {
        String p = created.get("temporaryPassword").asText();
        assertEquals("Srivasavi@123", p);
        return p;
    }

    // ---- who may use the admin area ----

    @Test
    void everyAdminEndpointRefusesEveryOtherRoleAndAnonymousCallers() throws Exception {
        db.faculty("f@test.edu", "F1", "CSE", "ASST_PROF");
        db.hod("h@test.edu", "CSE");
        db.user("p@test.edu", Role.PRINCIPAL);
        Object[][] calls = {
                {HttpMethod.GET, "/api/admin/reference", null},
                {HttpMethod.GET, "/api/admin/users", null},
                {HttpMethod.GET, "/api/admin/users/1", null},
                {HttpMethod.POST, "/api/admin/users", faculty("x@test.edu", "X1")},
                {HttpMethod.POST, "/api/admin/users/import", Map.of()},
                {HttpMethod.PUT, "/api/admin/users/1", Map.of("status", "DISABLED")},
                {HttpMethod.POST, "/api/admin/users/1/reset-password", null},
                {HttpMethod.POST, "/api/admin/users/reset-passwords", Map.of("ids", java.util.List.of(1))},
                {HttpMethod.POST, "/api/admin/departments", Map.of("code", "XYZ", "name", "X")},
                {HttpMethod.PUT, "/api/admin/departments/1", Map.of("name", "X")},
                {HttpMethod.POST, "/api/admin/academic-years", Map.of("name", "2030-31", "startDate", "2030-06-01", "endDate", "2031-05-31")},
                {HttpMethod.PUT, "/api/admin/academic-years/1", Map.of("active", false)},
                {HttpMethod.GET, "/api/admin/policies?academicYearId=1", null},
                {HttpMethod.POST, "/api/admin/policies", Map.of()},
                {HttpMethod.GET, "/api/admin/audit", null},
                {HttpMethod.DELETE, "/api/admin/audit", null},
                {HttpMethod.DELETE, "/api/admin/audit/1", null},
        };
        for (String who : new String[] {"f@test.edu", "h@test.edu", "p@test.edu"}) {
            MockHttpSession s = http.login(who);
            for (Object[] c : calls) {
                http.call(s, (HttpMethod) c[0], (String) c[1], c[2]).andExpect(status().isForbidden());
            }
        }
        for (Object[] c : calls) {
            http.call(null, (HttpMethod) c[0], (String) c[1], c[2]).andExpect(status().isUnauthorized());
        }
        // nothing was changed by the refused calls
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM users WHERE email = 'x@test.edu'").query(Integer.class).single());
    }

    @Test
    void referenceListsDepartmentsCadresYearsAndTheNineCriteria() throws Exception {
        JsonNode ref = http.read(http.get(admin, "/api/admin/reference").andExpect(status().isOk()));
        assertEquals(8, ref.get("departments").size());
        assertEquals(5, ref.get("cadres").size());
        assertTrue(ref.get("academicYears").size() >= 1);
        assertEquals(9, ref.get("criteria").size());
        assertEquals("TEACHING_LEARNING", ref.get("criteria").get(0).get("code").asText());
        assertEquals("Teaching & Learning", ref.get("criteria").get(0).get("label").asText());
    }

    // ---- creating accounts ----

    @Test
    void creatingAFacultyAccountGivesItTheStandardPasswordThatMustBeChanged() throws Exception {
        Map<String, Object> body = faculty("New.Faculty@Test.edu", "E-777");
        body.put("contactNo", "9876543210");
        body.put("phdStatus", "AWARDED");
        body.put("teachingExperienceYears", 6.5);
        body.put("joiningDateInstitution", "2019-07-01");
        JsonNode created = http.read(http.post(admin, "/api/admin/users", body).andExpect(status().isCreated()));
        String temp = createdPassword(created);
        assertEquals("new.faculty@test.edu", created.get("email").asText());     // stored in lower case
        assertEquals("FACULTY", created.get("role").asText());

        // stored hashed, forced change recorded
        String hash = jdbc.sql("SELECT password_hash FROM users WHERE email = 'new.faculty@test.edu'").query(String.class).single();
        assertNotEquals(temp, hash);
        assertTrue(hash.startsWith("$2"));
        assertTrue(jdbc.sql("SELECT must_change_password FROM users WHERE email = 'new.faculty@test.edu'").query(Boolean.class).single());

        // the faculty record exists with the details given
        var row = jdbc.sql("SELECT f.employee_id, f.name, f.contact_no, f.phd_status, f.teaching_experience_years, f.joining_date_institution, d.code, c.code AS cadre"
                + " FROM faculty_profiles f JOIN departments d ON d.id = f.department_id JOIN cadres c ON c.id = f.cadre_id"
                + " JOIN users u ON u.id = f.user_id WHERE u.email = 'new.faculty@test.edu'").query().singleRow();
        assertEquals("E-777", row.get("employee_id"));
        assertEquals("9876543210", row.get("contact_no"));
        assertEquals("AWARDED", row.get("phd_status"));
        assertEquals("CSE", row.get("code"));
        assertEquals("ASST_PROF", row.get("cadre"));
        assertEquals(0, new java.math.BigDecimal("6.5").compareTo((java.math.BigDecimal) row.get("teaching_experience_years")));

        // they can sign in with it, are held at the password change, then can start an appraisal
        MockHttpSession s = http.login("new.faculty@test.edu", temp);
        http.get(s, "/api/auth/me").andExpect(jsonPath("$.mustChangePassword").value(true)).andExpect(jsonPath("$.role").value("FACULTY"));
        http.post(s, "/api/appraisals", null).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
        http.post(s, "/api/auth/change-password", Map.of("currentPassword", temp, "newPassword", "maple-river-42")).andExpect(status().isNoContent());
        http.post(s, "/api/appraisals", null).andExpect(status().isCreated());
        // part A of the new appraisal starts from the record the administrator entered
        assertEquals("9876543210", jdbc.sql("SELECT contact_no FROM general_information").query(String.class).single());
    }

    @Test
    void creatingAHodNeedsDepartmentsAndGivesThemTheirReviewQueue() throws Exception {
        db.faculty("f@test.edu", "F1", "CSE", "ASST_PROF");
        JsonNode created = http.read(http.post(admin, "/api/admin/users",
                Map.of("role", "HOD", "email", "hod.new@test.edu", "hodDepartmentIds", List.of(cse, ece))).andExpect(status().isCreated()));
        createdPassword(created);
        assertEquals(2, jdbc.sql("SELECT COUNT(*) FROM hod_assignments a JOIN users u ON u.id = a.user_id WHERE u.email = 'hod.new@test.edu'")
                .query(Integer.class).single());
        JsonNode detail = http.read(http.get(admin, "/api/admin/users/" + created.get("id").asLong()).andExpect(status().isOk()));
        assertEquals(2, detail.get("hodDepartmentIds").size());
        assertEquals("CSE, ECE", detail.get("row").get("hodDepartments").asText());
    }

    @Test
    void principalDirectorAndAdminAccountsNeedOnlyAnEmail() throws Exception {
        for (String role : new String[] {"PRINCIPAL", "DIRECTOR", "ADMIN"}) {
            JsonNode created = http.read(http.post(admin, "/api/admin/users",
                    Map.of("role", role, "email", role.toLowerCase() + ".new@test.edu")).andExpect(status().isCreated()));
            createdPassword(created);
            assertEquals(role, created.get("role").asText());
            assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM faculty_profiles WHERE user_id = ?").param(created.get("id").asLong()).query(Integer.class).single());
        }
    }

    @Test
    void invalidAccountDetailsAreRefusedWithFieldErrorsAndNothingIsCreated() throws Exception {
        int before = jdbc.sql("SELECT COUNT(*) FROM users").query(Integer.class).single();
        post("/api/admin/users", Map.of(), "email", "role");
        post("/api/admin/users", Map.of("role", "FACULTY", "email", "not-an-email"), "email", "name", "employeeId", "departmentId", "cadreId");
        post("/api/admin/users", Map.of("role", "SUPERUSER", "email", "a@b.co"), "role");
        post("/api/admin/users", Map.of("role", "HOD", "email", "h@b.co"), "hodDepartmentIds");
        post("/api/admin/users", Map.of("role", "HOD", "email", "h@b.co", "hodDepartmentIds", List.of(999999)), "hodDepartmentIds");
        post("/api/admin/users", Map.of("role", "HOD", "email", "h@b.co", "hodDepartmentIds", "CSE"), "hodDepartmentIds");
        Map<String, Object> badDept = faculty("d@b.co", "D1");
        badDept.put("departmentId", 999999);
        post("/api/admin/users", badDept, "departmentId");
        Map<String, Object> badCadre = faculty("c@b.co", "C1");
        badCadre.put("cadreId", "abc");
        post("/api/admin/users", badCadre, "cadreId");
        Map<String, Object> badPhd = faculty("p@b.co", "P1");
        badPhd.put("phdStatus", "MAYBE");
        post("/api/admin/users", badPhd, "phdStatus");
        Map<String, Object> negative = faculty("n@b.co", "N1");
        negative.put("teachingExperienceYears", -2);
        post("/api/admin/users", negative, "teachingExperienceYears");
        Map<String, Object> longName = faculty("l@b.co", "L1");
        longName.put("name", "x".repeat(121));
        post("/api/admin/users", longName, "name");
        Map<String, Object> badDate = faculty("t@b.co", "T1");
        badDate.put("joiningDateInstitution", "01/07/2019");
        post("/api/admin/users", badDate, "joiningDateInstitution");
        assertEquals(before, jdbc.sql("SELECT COUNT(*) FROM users").query(Integer.class).single());
    }

    private void post(String path, Map<String, Object> body, String... fieldsWithErrors) throws Exception {
        JsonNode r = http.read(http.post(admin, path, body).andExpect(status().isBadRequest()));
        for (String f : fieldsWithErrors) {
            assertTrue(r.path("fieldErrors").has(f), "expected an error for " + f + " in " + r);
        }
    }

    @Test
    void aDepartmentThatIsClosedCannotReceiveNewPeople() throws Exception {
        long extra = http.read(http.post(admin, "/api/admin/departments", Map.of("code", "ISE", "name", "Information Science"))
                .andExpect(status().isCreated())).get("id").asLong();
        http.put(admin, "/api/admin/departments/" + extra, Map.of("name", "Information Science", "active", false)).andExpect(status().isOk());
        Map<String, Object> body = faculty("closed@test.edu", "CL1");
        body.put("departmentId", extra);
        post("/api/admin/users", body, "departmentId");
        post("/api/admin/users", Map.of("role", "HOD", "email", "closed.hod@test.edu", "hodDepartmentIds", List.of(extra)), "hodDepartmentIds");
    }

    @Test
    void duplicateEmailsAndEmployeeIdsAreRefusedWithoutLeavingHalfAnAccount() throws Exception {
        http.post(admin, "/api/admin/users", faculty("dup@test.edu", "E1")).andExpect(status().isCreated());
        post("/api/admin/users", faculty("DUP@test.edu", "E2"), "email");               // same address, different case
        post("/api/admin/users", faculty("other@test.edu", "E1"), "employeeId");         // same employee ID
        // the failed second attempt must not leave an account behind (the whole creation rolls back)
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM users WHERE email = 'other@test.edu'").query(Integer.class).single());
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM faculty_profiles WHERE employee_id = 'E1'").query(Integer.class).single());
    }

    // ---- reading ----

    @Test
    void listingAndSearchingUsersNeverExposesPasswordsOrHashes() throws Exception {
        db.faculty("asha@test.edu", "E001", "CSE", "ASST_PROF");
        db.faculty("ravi@test.edu", "E002", "ECE", "PROFESSOR");
        db.hod("hod@test.edu", "CSE");
        String all = http.get(admin, "/api/admin/users").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertFalse(all.replace("mustChangePassword", "").toLowerCase().contains("password"), all);   // the flag's name is not a secret
        assertFalse(all.contains("$2a$") || all.contains("$2b$"), all);
        assertEquals(4, json.readTree(all).size());

        assertEquals(1, http.read(http.get(admin, "/api/admin/users?q=asha")).size());
        assertEquals(1, http.read(http.get(admin, "/api/admin/users?q=E002")).size());       // by employee ID
        assertEquals(1, http.read(http.get(admin, "/api/admin/users?role=HOD")).size());
        assertEquals(0, http.read(http.get(admin, "/api/admin/users?q=%25")).size());       // % is not a wildcard
        assertEquals(0, http.read(http.get(admin, "/api/admin/users?q=nobody")).size());
        http.get(admin, "/api/admin/users?role=KING").andExpect(status().isBadRequest());

        long id = jdbc.sql("SELECT id FROM users WHERE email = 'asha@test.edu'").query(Long.class).single();
        String detail = http.get(admin, "/api/admin/users/" + id).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertFalse(detail.toLowerCase().contains("hash") || detail.contains("$2"), detail);
        assertEquals("E001", json.readTree(detail).get("row").get("employeeId").asText());
        http.get(admin, "/api/admin/users/999999").andExpect(status().isNotFound());
    }

    // ---- editing ----

    @Test
    void editingAFacultyRecordChangesTheDetailsButNeverTheRole() throws Exception {
        var f = db.faculty("edit@test.edu", "E010", "CSE", "ASST_PROF");
        Map<String, Object> body = faculty("edit@test.edu", "E010");
        body.put("name", "Dr. Renamed");
        body.put("departmentId", ece);
        body.put("cadreId", professor);
        body.put("role", "ADMIN");                          // must be ignored
        body.put("email", "changed@test.edu");              // e-mail is the sign-in name; not changed here
        http.put(admin, "/api/admin/users/" + f.id(), body).andExpect(status().isOk())
                .andExpect(jsonPath("$.row.name").value("Dr. Renamed")).andExpect(jsonPath("$.row.role").value("FACULTY"))
                .andExpect(jsonPath("$.departmentId").value(ece)).andExpect(jsonPath("$.cadreId").value(professor));
        assertEquals("FACULTY", jdbc.sql("SELECT role FROM users WHERE id = ?").param(f.id()).query(String.class).single());
        assertEquals("edit@test.edu", jdbc.sql("SELECT email FROM users WHERE id = ?").param(f.id()).query(String.class).single());

        // validation applies on edit as well, and an invalid edit changes nothing
        body.put("name", "");
        http.put(admin, "/api/admin/users/" + f.id(), body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.name").exists());
        assertEquals("Dr. Renamed", jdbc.sql("SELECT name FROM faculty_profiles WHERE user_id = ?").param(f.id()).query(String.class).single());
    }

    @Test
    void movingAnEmployeeIdOntoSomeoneElsesIsRefused() throws Exception {
        db.faculty("a@test.edu", "E1", "CSE", "ASST_PROF");
        var b = db.faculty("b@test.edu", "E2", "CSE", "ASST_PROF");
        http.put(admin, "/api/admin/users/" + b.id(), faculty("b@test.edu", "E1")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.employeeId").exists());
    }

    @Test
    void editingAHodReplacesTheirDepartments() throws Exception {
        var h = db.hod("h@test.edu", "CSE");
        http.put(admin, "/api/admin/users/" + h.id(), Map.of("hodDepartmentIds", List.of(ece))).andExpect(status().isOk())
                .andExpect(jsonPath("$.hodDepartmentIds[0]").value(ece));
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM hod_assignments WHERE user_id = ?").param(h.id()).query(Integer.class).single());
        http.put(admin, "/api/admin/users/" + h.id(), Map.of("hodDepartmentIds", List.of())).andExpect(status().isBadRequest());
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM hod_assignments WHERE user_id = ?").param(h.id()).query(Integer.class).single());
    }

    @Test
    void disablingEndsTheirSessionAtOnceAndEnablingLetsThemBackIn() throws Exception {
        var f = db.faculty("off@test.edu", "E5", "CSE", "ASST_PROF");
        MockHttpSession theirs = http.login("off@test.edu");
        http.get(theirs, "/api/appraisals").andExpect(status().isOk());

        http.put(admin, "/api/admin/users/" + f.id(), Map.of("status", "DISABLED")).andExpect(status().isOk())
                .andExpect(jsonPath("$.row.status").value("DISABLED"));
        http.get(theirs, "/api/appraisals").andExpect(status().isUnauthorized());
        assertEquals(401, http.tryLogin("off@test.edu", TestDb.PASSWORD).getResponse().getStatus());

        http.put(admin, "/api/admin/users/" + f.id(), Map.of("status", "ACTIVE")).andExpect(status().isOk());
        assertEquals(200, http.tryLogin("off@test.edu", TestDb.PASSWORD).getResponse().getStatus());

        http.put(admin, "/api/admin/users/" + f.id(), Map.of("status", "DELETED")).andExpect(status().isBadRequest());
    }

    @Test
    void anAdministratorCannotDisableThemselves() throws Exception {
        long me = jdbc.sql("SELECT id FROM users WHERE email = 'admin@test.edu'").query(Long.class).single();
        http.put(admin, "/api/admin/users/" + me, Map.of("status", "DISABLED")).andExpect(status().isConflict());
        http.get(admin, "/api/admin/users").andExpect(status().isOk());
    }

    @Test
    void resettingAPasswordEndsTheirSessionsAndForcesAChange() throws Exception {
        var f = db.faculty("reset@test.edu", "E6", "CSE", "ASST_PROF");
        MockHttpSession theirs = http.login("reset@test.edu");

        JsonNode issued = http.read(http.post(admin, "/api/admin/users/" + f.id() + "/reset-password", null).andExpect(status().isOk()));
        String temp = createdPassword(issued);

        http.get(theirs, "/api/auth/me").andExpect(status().isUnauthorized());                  // old session ended
        assertEquals(401, http.tryLogin("reset@test.edu", TestDb.PASSWORD).getResponse().getStatus());   // old password dead
        MockHttpSession fresh = http.login("reset@test.edu", temp);
        http.get(fresh, "/api/auth/me").andExpect(jsonPath("$.mustChangePassword").value(true));
        http.get(fresh, "/api/appraisals").andExpect(status().isForbidden());

        http.post(admin, "/api/admin/users/999999/reset-password", null).andExpect(status().isNotFound());
    }

    @Test
    void anAdministratorCannotResetTheirOwnPasswordThisWay() throws Exception {
        long me = jdbc.sql("SELECT id FROM users WHERE email = 'admin@test.edu'").query(Long.class).single();
        http.post(admin, "/api/admin/users/" + me + "/reset-password", null).andExpect(status().isConflict());
        http.get(admin, "/api/admin/users").andExpect(status().isOk());
    }

    // ---- many passwords at once ----

    @Test
    void severalPasswordsCanBeSetBackAtOnceAndTheAdministratorsOwnIsLeftAlone() throws Exception {
        var a = db.faculty("bulk1@test.edu", "B1", "CSE", "ASST_PROF");
        var c = db.faculty("bulk2@test.edu", "B2", "CSE", "PROFESSOR");
        var untouched = db.faculty("bulk3@test.edu", "B3", "CSE", "LECTURER");
        long me = jdbc.sql("SELECT id FROM users WHERE email = 'admin@test.edu'").query(Long.class).single();
        MockHttpSession theirs = http.login("bulk1@test.edu");

        JsonNode result = http.read(http.post(admin, "/api/admin/users/reset-passwords",
                Map.of("ids", java.util.List.of(a.id(), c.id(), c.id(), me, 999999))).andExpect(status().isOk()));
        assertEquals(2, result.get("reset").asInt());
        assertEquals(2, result.get("skipped").asInt());                      // the administrator and the id that matches nobody

        http.get(theirs, "/api/auth/me").andExpect(status().isUnauthorized());                 // their session ended
        assertEquals(401, http.tryLogin("bulk1@test.edu", TestDb.PASSWORD).getResponse().getStatus());   // old password dead
        MockHttpSession fresh = http.login("bulk2@test.edu", result.get("temporaryPassword").asText());
        http.get(fresh, "/api/auth/me").andExpect(jsonPath("$.mustChangePassword").value(true));
        assertEquals(200, http.tryLogin("bulk3@test.edu", TestDb.PASSWORD).getResponse().getStatus());    // not listed: untouched
        http.get(admin, "/api/admin/users").andExpect(status().isOk());                        // the administrator stays signed in

        assertEquals(2, jdbc.sql("SELECT COUNT(*) FROM audit_logs WHERE action = 'PASSWORD_RESET'").query(Integer.class).single());
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM audit_logs WHERE action = 'PASSWORDS_RESET_BULK'").query(Integer.class).single());
        for (String meta : jdbc.sql("SELECT COALESCE(CAST(metadata AS CHAR), '') FROM audit_logs").query(String.class).list()) {
            assertFalse(meta.contains(result.get("temporaryPassword").asText()) || meta.contains("$2a$"), meta);
        }
        assertEquals(untouched.id(), jdbc.sql("SELECT id FROM users WHERE email = 'bulk3@test.edu'").query(Long.class).single());
    }

    @Test
    void aBulkResetNeedsAListOfWholeNumberIdsOfAReasonableLength() throws Exception {
        http.post(admin, "/api/admin/users/reset-passwords", Map.of()).andExpect(status().isBadRequest());
        http.post(admin, "/api/admin/users/reset-passwords", Map.of("ids", java.util.List.of())).andExpect(status().isBadRequest());
        http.post(admin, "/api/admin/users/reset-passwords", Map.of("ids", java.util.List.of("x"))).andExpect(status().isBadRequest());
        http.post(admin, "/api/admin/users/reset-passwords", Map.of("ids", "1")).andExpect(status().isBadRequest());
        java.util.List<Integer> tooMany = new java.util.ArrayList<>();
        for (int i = 1; i <= 2001; i++) tooMany.add(i);
        http.post(admin, "/api/admin/users/reset-passwords", Map.of("ids", tooMany)).andExpect(status().isBadRequest());
    }

    // ---- departments ----

    @Test
    void departmentsCanBeAddedRenamedAndClosedButTheCodeNeverChanges() throws Exception {
        JsonNode d = http.read(http.post(admin, "/api/admin/departments", Map.of("code", "ise", "name", "Information Science")).andExpect(status().isCreated()));
        assertEquals("ISE", d.get("code").asText());                                      // upper-cased
        assertTrue(d.get("active").asBoolean());
        long id = d.get("id").asLong();

        http.put(admin, "/api/admin/departments/" + id, Map.of("name", "Info Science & Engg", "code", "OTHER")).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Info Science & Engg")).andExpect(jsonPath("$.code").value("ISE"))
                .andExpect(jsonPath("$.active").value(true));                              // active left alone when not given
        http.put(admin, "/api/admin/departments/" + id, Map.of("name", "Info Science & Engg", "active", false)).andExpect(jsonPath("$.active").value(false));

        post("/api/admin/departments", Map.of("code", "ISE", "name", "Again"), "code");     // duplicate
        post("/api/admin/departments", Map.of("code", "bad code!", "name", "X"), "code");
        post("/api/admin/departments", Map.of("code", "A", "name", "X"), "code");
        post("/api/admin/departments", Map.of("code", "OK1", "name", ""), "name");
        http.put(admin, "/api/admin/departments/" + id, Map.of("name", "X", "active", "yes")).andExpect(status().isBadRequest());
        http.put(admin, "/api/admin/departments/999999", Map.of("name", "X")).andExpect(status().isNotFound());
    }

    // ---- academic years ----

    @Test
    void openingAYearCopiesEachCadresLatestPolicyAndNewAppraisalsUseIt() throws Exception {
        JsonNode y = http.read(http.post(admin, "/api/admin/academic-years",
                Map.of("name", "2027-28", "startDate", "2027-06-01", "endDate", "2028-05-31")).andExpect(status().isCreated()));
        long yearId = y.get("id").asLong();
        assertTrue(y.get("active").asBoolean());

        assertEquals(5, jdbc.sql("SELECT COUNT(*) FROM scoring_policies WHERE academic_year_id = ? AND version = 1").param(yearId).query(Integer.class).single());
        assertEquals(45, jdbc.sql("SELECT COUNT(*) FROM scoring_policy_criteria c JOIN scoring_policies p ON p.id = c.policy_id WHERE p.academic_year_id = ?")
                .param(yearId).query(Integer.class).single());
        // the copy matches the earlier year's current marks (its latest version) cadre by cadre
        assertEquals(0, jdbc.sql("""
                SELECT COUNT(*) FROM scoring_policy_criteria a
                JOIN scoring_policies pa ON pa.id = a.policy_id AND pa.academic_year_id = ?
                JOIN scoring_policies pb ON pb.academic_year_id = (SELECT id FROM academic_years WHERE name = '2025-26') AND pb.cadre_id = pa.cadre_id AND pb.version = 3
                JOIN scoring_policy_criteria b ON b.policy_id = pb.id AND b.criterion = a.criterion
                WHERE a.max_marks <> b.max_marks""").param(yearId).query(Integer.class).single());

        // the new year is the latest open one, so a faculty member now starts an appraisal in it
        db.faculty("f@test.edu", "F1", "CSE", "ASST_PROF");
        MockHttpSession f = http.login("f@test.edu");
        http.post(f, "/api/appraisals", null).andExpect(status().isCreated());
        assertEquals("2027-28", jdbc.sql("SELECT ay.name FROM appraisals a JOIN academic_years ay ON ay.id = a.academic_year_id").query(String.class).single());
        assertEquals(85, jdbc.sql("SELECT SUM(max_marks) FROM appraisal_scores").query(Integer.class).single()); // B1 to B4 only: 40 + 15 + 15 + 15
    }

    @Test
    void closingTheLatestYearSendsNewAppraisalsBackToTheOneBeforeIt() throws Exception {
        long newer = http.read(http.post(admin, "/api/admin/academic-years",
                Map.of("name", "2027-28", "startDate", "2027-06-01", "endDate", "2028-05-31"))).get("id").asLong();
        http.put(admin, "/api/admin/academic-years/" + newer, Map.of("active", false)).andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        db.faculty("f@test.edu", "F1", "CSE", "ASST_PROF");
        http.post(http.login("f@test.edu"), "/api/appraisals", null).andExpect(status().isCreated());
        assertEquals("2025-26", jdbc.sql("SELECT ay.name FROM appraisals a JOIN academic_years ay ON ay.id = a.academic_year_id").query(String.class).single());
    }

    @Test
    void invalidYearsAreRefused() throws Exception {
        for (String name : new String[] {"2027-29", "2027-2028", "27-28", "abcd-ef", "2099-00x", ""}) {
            post("/api/admin/academic-years", Map.of("name", name, "startDate", "2030-06-01", "endDate", "2031-05-31"), "name");
        }
        post("/api/admin/academic-years", Map.of("name", "2030-31", "startDate", "2031-06-01", "endDate", "2030-05-31"), "endDate");
        post("/api/admin/academic-years", Map.of("name", "2030-31", "startDate", "2030-06-01", "endDate", "2030-06-01"), "endDate");
        post("/api/admin/academic-years", Map.of("name", "2030-31", "startDate", "June 1", "endDate", "2031-05-31"), "startDate");
        post("/api/admin/academic-years", Map.of("name", "2025-26", "startDate", "2025-06-01", "endDate", "2026-05-31"), "name");   // exists
        http.put(admin, "/api/admin/academic-years/999999", Map.of("active", false)).andExpect(status().isNotFound());
        http.put(admin, "/api/admin/academic-years/" + yearId(), Map.of()).andExpect(status().isBadRequest());      // active is required
        // the century rollover is a valid name
        http.post(admin, "/api/admin/academic-years", Map.of("name", "2099-00", "startDate", "2099-06-01", "endDate", "2100-05-31"))
                .andExpect(status().isCreated());
    }

    // ---- scoring policy ----

    /** The four criteria that have a maximum (B1 to B4); B5 to B9 are marked per entry and are not given. */
    private Map<String, Object> marks(int teaching, int mentoring) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("TEACHING_LEARNING", teaching);
        m.put("STUDENT_MENTORING", mentoring);
        m.put("FDP_CERTIFICATIONS", 10);
        m.put("ADMINISTRATIVE", 15);
        return m;
    }

    private long yearId() {
        return jdbc.sql("SELECT id FROM academic_years WHERE name = '2025-26'").query(Long.class).single();
    }

    private Map<String, Object> publish(Map<String, Object> marks) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("academicYearId", yearId());
        b.put("cadreId", asstProf);
        b.put("marks", marks);
        return b;
    }

    @Test
    void policiesListEveryVersionWithTheirTotals() throws Exception {
        JsonNode all = http.read(http.get(admin, "/api/admin/policies?academicYearId=" + yearId()).andExpect(status().isOk()));
        assertEquals(15, all.size());                              // three versions of each of the five cadres' policies
        for (JsonNode p : all) {
            assertTrue(List.of(1, 2, 3).contains(p.get("version").asInt()));
            assertEquals(9, p.get("marks").size());
            assertEquals(0, p.get("marks").get("RESEARCH_PUBLICATIONS").asInt()); // marked per entry, no maximum
        }
        http.get(admin, "/api/admin/policies?academicYearId=999999").andExpect(status().isNotFound());
        http.get(admin, "/api/admin/policies").andExpect(status().isBadRequest());
    }

    @Test
    void publishingAPolicyMakesANewVersionOnlyNewAppraisalsUse() throws Exception {
        db.faculty("old@test.edu", "F1", "CSE", "ASST_PROF");
        db.faculty("new@test.edu", "F2", "CSE", "ASST_PROF");
        http.post(http.login("old@test.edu"), "/api/appraisals", null).andExpect(status().isCreated());

        JsonNode v2 = http.read(http.post(admin, "/api/admin/policies", publish(marks(25, 20))).andExpect(status().isCreated()));
        assertEquals(4, v2.get("version").asInt());          // after versions 1 (the document's), 2 (V24's) and 3 (V33's current policy)
        assertEquals(70, v2.get("total").asInt());
        assertEquals(25, v2.get("marks").get("TEACHING_LEARNING").asInt());

        // the appraisal already started keeps the maxima it began with ...
        assertEquals(40, jdbc.sql("SELECT s.max_marks FROM appraisal_scores s WHERE s.criterion = 'TEACHING_LEARNING'").query(Integer.class).single());
        // ... a later one gets the new version
        http.post(http.login("new@test.edu"), "/api/appraisals", null).andExpect(status().isCreated());
        assertEquals(List.of(40, 25), jdbc.sql("SELECT s.max_marks FROM appraisal_scores s JOIN appraisals a ON a.id = s.appraisal_id"
                + " WHERE s.criterion = 'TEACHING_LEARNING' ORDER BY a.id").query(Integer.class).list());
        assertEquals(4, jdbc.sql("SELECT MAX(version) FROM scoring_policies WHERE cadre_id = ?").param(asstProf).query(Integer.class).single());

        // the next publish is version 5; the other cadres were not touched
        JsonNode v3 = http.read(http.post(admin, "/api/admin/policies", publish(marks(20, 25))));
        assertEquals(5, v3.get("version").asInt());
        assertEquals(3, jdbc.sql("SELECT MAX(version) FROM scoring_policies WHERE cadre_id = ?").param(professor).query(Integer.class).single());
    }

    @Test
    void policiesWithMissingOrInvalidMarksAreRefusedAndTheMarkedPerEntryCriteriaNeedNone() throws Exception {
        Map<String, Object> missing = marks(30, 15);
        missing.remove("ADMINISTRATIVE");
        post("/api/admin/policies", publish(missing), "marks.ADMINISTRATIVE");
        Map<String, Object> unknown = marks(30, 15);
        unknown.put("BONUS", 0);
        post("/api/admin/policies", publish(unknown), "marks");
        Map<String, Object> negative = marks(30, 15);
        negative.put("ADMINISTRATIVE", -5);
        post("/api/admin/policies", publish(negative), "marks.ADMINISTRATIVE");
        Map<String, Object> fraction = marks(30, 15);
        fraction.put("ADMINISTRATIVE", 4.5);
        post("/api/admin/policies", publish(fraction), "marks.ADMINISTRATIVE");
        Map<String, Object> text = marks(30, 15);
        text.put("ADMINISTRATIVE", "five");
        post("/api/admin/policies", publish(text), "marks.ADMINISTRATIVE");
        Map<String, Object> huge = marks(30, 15);
        huge.put("ADMINISTRATIVE", 1000);
        post("/api/admin/policies", publish(huge), "marks.ADMINISTRATIVE");
        Map<String, Object> noMarks = publish(marks(30, 15));
        noMarks.remove("marks");
        post("/api/admin/policies", noMarks, "marks");
        Map<String, Object> badYear = publish(marks(30, 15));
        badYear.put("academicYearId", 999999);
        post("/api/admin/policies", badYear, "academicYearId");
        Map<String, Object> badCadre = publish(marks(30, 15));
        badCadre.put("cadreId", null);
        post("/api/admin/policies", badCadre, "cadreId");
        // a policy whose marks are right is still accepted after all those refusals
        http.post(admin, "/api/admin/policies", publish(marks(30, 15))).andExpect(status().isCreated());
        assertEquals(4, jdbc.sql("SELECT MAX(version) FROM scoring_policies WHERE cadre_id = ?").param(asstProf).query(Integer.class).single());
    }

    // ---- audit trail ----

    @Test
    void theAuditTrailRecordsAdministrationWithoutAnySecret() throws Exception {
        JsonNode created = http.read(http.post(admin, "/api/admin/users", faculty("aud@test.edu", "A1")).andExpect(status().isCreated()));
        String temp = createdPassword(created);
        long id = created.get("id").asLong();
        http.put(admin, "/api/admin/users/" + id, Map.of("status", "DISABLED"));
        JsonNode reset = http.read(http.post(admin, "/api/admin/users/" + id + "/reset-password", null));
        http.post(admin, "/api/admin/departments", Map.of("code", "ISE", "name", "Information Science"));
        http.post(admin, "/api/admin/policies", publish(marks(25, 20)));

        JsonNode page = http.read(http.get(admin, "/api/admin/audit?size=100").andExpect(status().isOk()));
        List<String> actions = new java.util.ArrayList<>();
        page.get("items").forEach(i -> actions.add(i.get("action").asText()));
        for (String expected : new String[] {"USER_CREATED", "USER_DISABLED", "USER_UPDATED", "PASSWORD_RESET", "DEPARTMENT_CREATED", "SCORING_POLICY_PUBLISHED"}) {
            assertTrue(actions.contains(expected), expected + " missing from " + actions);
        }
        JsonNode first = page.get("items").get(0);
        assertEquals("admin@test.edu", first.get("actorEmail").asText());
        assertEquals("SCORING_POLICY_PUBLISHED", first.get("action").asText());                    // newest first

        String text = page.toString();
        assertFalse(text.contains(temp) || text.contains(reset.get("temporaryPassword").asText()), "a one-time password reached the audit trail");
        for (String meta : jdbc.sql("SELECT COALESCE(CAST(metadata AS CHAR), '') FROM audit_logs").query(String.class).list()) {
            assertFalse(meta.contains(temp) || meta.contains("$2a$") || meta.contains("$2b$"), meta);
        }
    }

    @Test
    void theAuditTrailIsPagedFilteredAndCapped() throws Exception {
        for (int i = 0; i < 7; i++) {
            http.post(admin, "/api/admin/departments", Map.of("code", "D" + i + "X", "name", "Dept " + i)).andExpect(status().isCreated());
        }
        JsonNode p0 = http.read(http.get(admin, "/api/admin/audit?size=3&page=0").andExpect(status().isOk()));
        JsonNode p1 = http.read(http.get(admin, "/api/admin/audit?size=3&page=1"));
        assertEquals(3, p0.get("items").size());
        assertEquals(7, p0.get("total").asLong());
        assertEquals(3, p1.get("items").size());
        assertTrue(p0.get("items").get(2).get("id").asLong() > p1.get("items").get(0).get("id").asLong());   // descending, no overlap
        assertEquals(1, http.read(http.get(admin, "/api/admin/audit?size=3&page=2")).get("items").size());
        assertEquals(0, http.read(http.get(admin, "/api/admin/audit?size=3&page=9")).get("items").size());

        assertEquals(7, http.read(http.get(admin, "/api/admin/audit?action=DEPARTMENT_CREATED")).get("total").asLong());
        assertEquals(0, http.read(http.get(admin, "/api/admin/audit?action=NO_SUCH_ACTION")).get("total").asLong());
        assertEquals(7, http.read(http.get(admin, "/api/admin/audit?entityType=DEPARTMENT")).get("total").asLong());
        long someDept = p0.get("items").get(0).get("entityId").asLong();
        assertEquals(1, http.read(http.get(admin, "/api/admin/audit?entityType=DEPARTMENT&entityId=" + someDept)).get("total").asLong());

        http.get(admin, "/api/admin/audit?size=101").andExpect(status().isBadRequest());
        http.get(admin, "/api/admin/audit?size=0").andExpect(status().isBadRequest());
        http.get(admin, "/api/admin/audit?page=-1").andExpect(status().isBadRequest());
        http.get(admin, "/api/admin/audit?size=abc").andExpect(status().isBadRequest());
    }

    @Test
    void adminOnlyEndpointsRejectMalformedBodiesAsClientErrors() throws Exception {
        for (String path : new String[] {"/api/admin/users", "/api/admin/departments", "/api/admin/policies", "/api/admin/academic-years"}) {
            http.post(admin, path, "{nope").andExpect(status().isBadRequest());
            http.post(admin, path, "[]").andExpect(status().isBadRequest());
        }
    }

    @Test
    void anAdministratorCanDeleteAuditEntriesAndTheDeletionIsRecorded() throws Exception {
        for (int i = 0; i < 4; i++) {
            http.post(admin, "/api/admin/departments", Map.of("code", "DEL" + i, "name", "Dept " + i)).andExpect(status().isCreated());
        }
        http.post(admin, "/api/admin/academic-years", Map.of("name", "2040-41", "startDate", "2040-06-01", "endDate", "2041-05-31")).andExpect(status().isCreated());
        long total = http.read(http.get(admin, "/api/admin/audit")).get("total").asLong();
        assertTrue(total >= 5);

        // one entry
        long one = http.read(http.get(admin, "/api/admin/audit?action=ACADEMIC_YEAR_CREATED")).get("items").get(0).get("id").asLong();
        assertEquals(1, http.read(http.call(admin, HttpMethod.DELETE, "/api/admin/audit/" + one, null).andExpect(status().isOk())).get("removed").asInt());
        assertEquals(0, http.read(http.get(admin, "/api/admin/audit?action=ACADEMIC_YEAR_CREATED")).get("total").asInt());
        http.call(admin, HttpMethod.DELETE, "/api/admin/audit/" + one, null).andExpect(status().isNotFound());

        // every entry of one kind
        assertEquals(4, http.read(http.call(admin, HttpMethod.DELETE, "/api/admin/audit?action=DEPARTMENT_CREATED", null)).get("removed").asInt());
        assertEquals(0, http.read(http.get(admin, "/api/admin/audit?action=DEPARTMENT_CREATED")).get("total").asInt());

        // everything: what remains is the record of the deletions, naming who did it and how many went
        http.call(admin, HttpMethod.DELETE, "/api/admin/audit", null).andExpect(status().isOk());
        JsonNode left = http.read(http.get(admin, "/api/admin/audit?size=100"));
        for (JsonNode e : left.get("items")) {
            assertEquals("AUDIT_DELETED", e.get("action").asText());
            assertEquals("admin@test.edu", e.get("actorEmail").asText());
        }
        assertEquals(1, left.get("total").asInt());
        // entries still cannot be edited
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataAccessException.class,
                () -> jdbc.sql("UPDATE audit_logs SET action = 'TAMPERED'").update());
    }

    @Test
    void auditEntriesAboutAppraisalsLeaveOutFileNamesAndChecksums() throws Exception {
        var f = db.faculty("f@test.edu", "F1", "CSE", "ASST_PROF");
        audit.recordDetails(f.id(), "DOCUMENT_UPLOADED", "APPRAISAL", 1L,
                Map.of("documentId", 7, "category", "FDP", "name", "medical-certificate.pdf", "size", 1234));
        audit.recordDetails(f.id(), "REPORT_ISSUED", "APPRAISAL", 1L, Map.of("sha256", "abc123", "bytes", 99));
        audit.record(f.id(), "SECTION_SAVED", "APPRAISAL", 1L, "{\"section\":\"publications\",\"changes\":2}");

        JsonNode page = http.read(http.get(admin, "/api/admin/audit?entityType=APPRAISAL").andExpect(status().isOk()));
        assertEquals(3, page.get("total").asInt());
        String text = page.toString();
        assertFalse(text.contains("medical-certificate") || text.contains("abc123"), text);
        JsonNode upload = null;
        for (JsonNode i : page.get("items")) {
            if (i.get("action").asText().equals("DOCUMENT_UPLOADED")) upload = i;
        }
        assertEquals(7, upload.get("details").get("documentId").asInt());
        assertEquals("FDP", upload.get("details").get("category").asText());
        assertFalse(upload.get("details").has("name"));
        // ... while the stored entry itself is complete
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM audit_logs WHERE CAST(metadata AS CHAR) LIKE '%medical-certificate.pdf%'").query(Integer.class).single());
    }
}
