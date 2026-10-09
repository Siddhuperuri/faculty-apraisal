package edu.svec.fams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.svec.fams.auth.Role;
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

/** Changing your own password, the forced change after an administrator issues one, and sessions ending on the server's say-so. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountSecurityTest {

    static final String NEW_PASSWORD = "maple-river-42";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestDb db;
    @Autowired JdbcClient jdbc;

    TestHttp http;

    @BeforeEach
    void setUp() {
        db.reset();
        http = new TestHttp(mvc, json);
        db.faculty("faculty@test.edu", "E001", "CSE", "ASST_PROF");
        db.user("admin@test.edu", Role.ADMIN);
    }

    private Map<String, String> change(String current, String next) {
        return Map.of("currentPassword", current, "newPassword", next);
    }

    // ---- changing the password ----

    @Test
    void changingThePasswordWorksAndTheOldOneStopsWorking() throws Exception {
        MockHttpSession s = http.login("faculty@test.edu");
        http.post(s, "/api/auth/change-password", change(TestDb.PASSWORD, NEW_PASSWORD)).andExpect(status().isNoContent());

        assertEquals(401, http.tryLogin("faculty@test.edu", TestDb.PASSWORD).getResponse().getStatus());
        assertEquals(200, http.tryLogin("faculty@test.edu", NEW_PASSWORD).getResponse().getStatus());
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM audit_logs WHERE action = 'PASSWORD_CHANGED'").query(Integer.class).single());
    }

    @Test
    void theCallersOwnSessionSurvivesButOtherSessionsEnd() throws Exception {
        MockHttpSession here = http.login("faculty@test.edu");
        MockHttpSession elsewhere = http.login("faculty@test.edu");
        http.get(elsewhere, "/api/auth/me").andExpect(status().isOk());

        http.post(here, "/api/auth/change-password", change(TestDb.PASSWORD, NEW_PASSWORD)).andExpect(status().isNoContent());

        http.get(here, "/api/auth/me").andExpect(status().isOk());
        http.get(elsewhere, "/api/auth/me").andExpect(status().isUnauthorized());
        http.get(elsewhere, "/api/appraisals").andExpect(status().isUnauthorized());
    }

    @Test
    void aWrongCurrentPasswordIsRefusedWithAFieldError() throws Exception {
        MockHttpSession s = http.login("faculty@test.edu");
        http.post(s, "/api/auth/change-password", change("not-the-password", NEW_PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.currentPassword").exists());
        assertEquals(200, http.tryLogin("faculty@test.edu", TestDb.PASSWORD).getResponse().getStatus());
    }

    @Test
    void weakNewPasswordsAreRefusedWithAReason() throws Exception {
        MockHttpSession s = http.login("faculty@test.edu");
        String[] weak = {"short1", "onlylettersnodigits", "12345678901234", "faculty-2025-x", TestDb.PASSWORD,
                "Password123", " space-in-front-1", "a1" + "x".repeat(80)};
        for (String candidate : weak) {
            http.post(s, "/api/auth/change-password", change(TestDb.PASSWORD, candidate))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors.newPassword").exists());
        }
        // nothing changed
        assertEquals(200, http.tryLogin("faculty@test.edu", TestDb.PASSWORD).getResponse().getStatus());
    }

    @Test
    void blankFieldsAreFieldErrorsNotServerErrors() throws Exception {
        MockHttpSession s = http.login("faculty@test.edu");
        http.post(s, "/api/auth/change-password", Map.of("currentPassword", "", "newPassword", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.currentPassword").exists())
                .andExpect(jsonPath("$.fieldErrors.newPassword").exists());
        http.post(s, "/api/auth/change-password", "{}").andExpect(status().isBadRequest());
    }

    @Test
    void guessingTheCurrentPasswordIsRateLimited() throws Exception {
        MockHttpSession s = http.login("faculty@test.edu");
        for (int i = 0; i < 5; i++) {
            http.post(s, "/api/auth/change-password", change("guess-" + i, NEW_PASSWORD)).andExpect(status().isBadRequest());
        }
        // even the right password is held back for a while
        http.post(s, "/api/auth/change-password", change(TestDb.PASSWORD, NEW_PASSWORD)).andExpect(status().isTooManyRequests());
    }

    @Test
    void changingThePasswordNeedsASessionAndACsrfToken() throws Exception {
        http.post(null, "/api/auth/change-password", change(TestDb.PASSWORD, NEW_PASSWORD)).andExpect(status().isUnauthorized());
        MockHttpSession s = http.login("faculty@test.edu");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/auth/change-password")
                        .session(s).contentType("application/json")
                        .content(json.writeValueAsString(change(TestDb.PASSWORD, NEW_PASSWORD))))
                .andExpect(status().isForbidden());
    }

    @Test
    void passwordsAreNeverAuditedOrReturned() throws Exception {
        MockHttpSession s = http.login("faculty@test.edu");
        String body = http.post(s, "/api/auth/change-password", change(TestDb.PASSWORD, NEW_PASSWORD))
                .andReturn().getResponse().getContentAsString();
        assertEquals("", body);
        for (String meta : jdbc.sql("SELECT COALESCE(CAST(metadata AS CHAR), '') FROM audit_logs").query(String.class).list()) {
            assertFalse(meta.contains(NEW_PASSWORD) || meta.contains(TestDb.PASSWORD), meta);
        }
        assertFalse(http.get(s, "/api/auth/me").andReturn().getResponse().getContentAsString().toLowerCase().contains("hash"));
    }

    // ---- the forced change ----

    @Test
    void whileAPasswordChangeIsPendingEverythingElseIsRefused() throws Exception {
        jdbc.sql("UPDATE users SET must_change_password = TRUE WHERE email = 'faculty@test.edu'").update();
        MockHttpSession s = http.login("faculty@test.edu");

        http.get(s, "/api/auth/me").andExpect(status().isOk()).andExpect(jsonPath("$.mustChangePassword").value(true));
        http.get(s, "/api/auth/csrf").andExpect(status().isOk());
        for (String path : new String[] {"/api/appraisals", "/api/sections/meta"}) {
            http.get(s, path).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
        }
        http.post(s, "/api/appraisals", null).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));

        http.post(s, "/api/auth/change-password", change(TestDb.PASSWORD, NEW_PASSWORD)).andExpect(status().isNoContent());

        http.get(s, "/api/auth/me").andExpect(jsonPath("$.mustChangePassword").value(false));
        http.get(s, "/api/appraisals").andExpect(status().isOk());
        http.post(s, "/api/appraisals", null).andExpect(status().isCreated());
    }

    @Test
    void aPendingChangeAlsoBlocksTheAdminArea() throws Exception {
        jdbc.sql("UPDATE users SET must_change_password = TRUE WHERE email = 'admin@test.edu'").update();
        MockHttpSession s = http.login("admin@test.edu");
        http.get(s, "/api/admin/users").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
    }

    @Test
    void aUserWithAPendingChangeCanStillSignOut() throws Exception {
        jdbc.sql("UPDATE users SET must_change_password = TRUE WHERE email = 'faculty@test.edu'").update();
        MockHttpSession s = http.login("faculty@test.edu");
        http.post(s, "/api/auth/logout", null).andExpect(status().isNoContent());
        http.get(s, "/api/auth/me").andExpect(status().isUnauthorized());
    }

    // ---- the server ending sessions ----

    @Test
    void disablingAnAccountEndsItsSessionAtOnce() throws Exception {
        MockHttpSession s = http.login("faculty@test.edu");
        http.get(s, "/api/appraisals").andExpect(status().isOk());
        jdbc.sql("UPDATE users SET status = 'DISABLED' WHERE email = 'faculty@test.edu'").update();
        http.get(s, "/api/appraisals").andExpect(status().isUnauthorized());
        http.get(s, "/api/auth/me").andExpect(status().isUnauthorized());
        assertEquals(401, http.tryLogin("faculty@test.edu", TestDb.PASSWORD).getResponse().getStatus());
    }

    @Test
    void changingAnAccountsRoleEndsItsSessionAtOnce() throws Exception {
        MockHttpSession s = http.login("faculty@test.edu");
        jdbc.sql("UPDATE users SET role = 'HOD' WHERE email = 'faculty@test.edu'").update();
        http.get(s, "/api/appraisals").andExpect(status().isUnauthorized());
    }

    @Test
    void aDeletedAccountsSessionEnds() throws Exception {
        db.user("temp@test.edu", Role.PRINCIPAL);
        MockHttpSession s = http.login("temp@test.edu");
        jdbc.sql("DELETE FROM users WHERE email = 'temp@test.edu'").update();
        http.get(s, "/api/auth/me").andExpect(status().isUnauthorized());
    }

    @Test
    void signingInRecordsTheLastLoginAndTheSessionVersion() throws Exception {
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM users WHERE email = 'faculty@test.edu' AND last_login_at IS NULL").query(Integer.class).single());
        MockHttpSession s = http.login("faculty@test.edu");
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM users WHERE email = 'faculty@test.edu' AND last_login_at IS NOT NULL").query(Integer.class).single());
        assertNotNull(s.getAttribute("FAMS_SESSION_VERSION"));
        assertTrue((Integer) s.getAttribute("FAMS_SESSION_VERSION") >= 0);
    }

    @Test
    void failedSignInsDoNotTouchTheAccount() throws Exception {
        http.tryLogin("faculty@test.edu", "nope");
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM users WHERE email = 'faculty@test.edu' AND last_login_at IS NULL").query(Integer.class).single());
    }

    @Test
    void aSignedOutSessionStaysSignedOut() throws Exception {
        MockHttpSession s = http.login("faculty@test.edu");
        http.call(s, HttpMethod.POST, "/api/auth/logout", null).andExpect(status().isNoContent());
        http.get(s, "/api/appraisals").andExpect(status().isUnauthorized());
    }
}
