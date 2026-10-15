package edu.svec.fams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.svec.fams.auth.FamsUserPrincipal;
import edu.svec.fams.auth.Role;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The administrator can correct a user's e-mail address (the sign-in name) from Edit user. The person is signed out and uses
 * the new address with the same password; an address already in use, or a malformed one, is refused; every change is audited.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EmailChangeIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestDb db;
    @Autowired JdbcClient jdbc;

    TestHttp http;
    MockHttpSession admin;
    FamsUserPrincipal faculty, other, adminUser;

    @BeforeEach
    void setUp() throws Exception {
        db.reset();
        http = new TestHttp(mvc, json);
        adminUser = db.user("admin@test.edu", Role.ADMIN);
        db.user("admin2@test.edu", Role.ADMIN);
        faculty = db.faculty("old.name@test.edu", "E001", "CSE", "ASST_PROF");
        other = db.faculty("taken@test.edu", "E002", "CSE", "ASST_PROF");
        admin = http.login("admin@test.edu");
    }

    private String emailOf(long id) {
        return jdbc.sql("SELECT email FROM users WHERE id = ?").param(id).query(String.class).single();
    }

    @Test
    void theAddressIsChangedAndThePersonSignsInWithTheNewOneAndTheSamePassword() throws Exception {
        MockHttpSession before = http.login("old.name@test.edu");
        http.get(before, "/api/auth/me").andExpect(status().isOk());

        http.put(admin, "/api/admin/users/" + faculty.id(), Map.of("email", "  New.Name@Test.edu "))
                .andExpect(status().isOk()).andExpect(jsonPath("$.row.email").value("new.name@test.edu"));
        assertEquals("new.name@test.edu", emailOf(faculty.id()));

        // the old session ends, the old address no longer signs in, and the new one does with the unchanged password
        http.get(before, "/api/auth/me").andExpect(status().isUnauthorized());
        http.login("new.name@test.edu").getId();
        http.call(null, org.springframework.http.HttpMethod.POST, "/api/auth/login",
                Map.of("email", "old.name@test.edu", "password", TestDb.PASSWORD)).andExpect(status().isUnauthorized());

        Map<String, Object> audited = jdbc.sql("SELECT metadata FROM audit_logs WHERE action = 'USER_EMAIL_CHANGED'").query().singleRow();
        assertTrue(audited.get("metadata").toString().contains("old.name@test.edu")
                && audited.get("metadata").toString().contains("new.name@test.edu"));
    }

    @Test
    void anAddressInUseOrMalformedOrMissingIsRefusedAndNothingChanges() throws Exception {
        http.put(admin, "/api/admin/users/" + faculty.id(), Map.of("email", "TAKEN@test.edu"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.email").exists());
        http.put(admin, "/api/admin/users/" + faculty.id(), Map.of("email", "not-an-address"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.email").exists());
        http.put(admin, "/api/admin/users/" + faculty.id(), Map.of("email", "   "))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.email").exists());
        assertEquals("old.name@test.edu", emailOf(faculty.id()));
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM audit_logs WHERE action = 'USER_EMAIL_CHANGED'").query(Integer.class).single());
    }

    @Test
    void sendingTheSameAddressOrNoneChangesNothing() throws Exception {
        long version = jdbc.sql("SELECT session_version FROM users WHERE id = ?").param(faculty.id()).query(Long.class).single();
        http.put(admin, "/api/admin/users/" + faculty.id(), Map.of("email", "OLD.name@test.edu")).andExpect(status().isOk());
        http.put(admin, "/api/admin/users/" + faculty.id(), Map.of("name", "Dr. Someone")).andExpect(status().isOk());
        assertEquals("old.name@test.edu", emailOf(faculty.id()));
        assertEquals(version, jdbc.sql("SELECT session_version FROM users WHERE id = ?").param(faculty.id()).query(Long.class).single());
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM audit_logs WHERE action = 'USER_EMAIL_CHANGED'").query(Integer.class).single());
    }

    @Test
    void anAdministratorCannotChangeTheirOwnAddressHereAndOtherRolesCannotChangeAnyone() throws Exception {
        http.put(admin, "/api/admin/users/" + adminUser.id(), Map.of("email", "me.new@test.edu")).andExpect(status().isConflict());
        assertEquals("admin@test.edu", emailOf(adminUser.id()));

        db.hod("hod@test.edu", "CSE");
        MockHttpSession hod = http.login("hod@test.edu");
        http.put(hod, "/api/admin/users/" + faculty.id(), Map.of("email", "x@test.edu")).andExpect(status().isForbidden());
        // another administrator can change this administrator's address
        MockHttpSession second = http.login("admin2@test.edu");
        http.put(second, "/api/admin/users/" + adminUser.id(), Map.of("email", "admin.new@test.edu")).andExpect(status().isOk());
        assertEquals("admin.new@test.edu", emailOf(adminUser.id()));
    }
}

