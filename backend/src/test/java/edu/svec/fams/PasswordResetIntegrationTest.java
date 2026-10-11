package edu.svec.fams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.svec.fams.auth.FamsUserPrincipal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
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
 * "Forgot password": a person asks for a link by e-mail and chooses their own new password with it. The answer never says
 * whether an address has an account; only a hash of the token is kept; a link works once and expires; requests are limited.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PasswordResetIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestDb db;
    @Autowired JdbcClient jdbc;

    FamsUserPrincipal faculty;

    @BeforeEach
    void setUp() {
        db.reset();
        jdbc.sql("DELETE FROM password_reset_tokens").update();
        faculty = db.faculty("f1@test.edu", "E001", "CSE", "ASST_PROF");
    }

    private ResultActions send(String path, Map<String, Object> body, String address) throws Exception {
        return mvc.perform(post(path).with(csrf().asHeader()).with(r -> {
            r.setRemoteAddr(address);
            return r;
        }).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private ResultActions forgot(String email, String address) throws Exception {
        return send("/api/auth/forgot-password", Map.of("email", email), address);
    }

    private static String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    /** What the e-mailed link would carry: a token whose hash is stored, as the application would have done. */
    private String issueToken(long userId, String minutes) throws Exception {
        String token = "tok-" + java.util.UUID.randomUUID() + "-" + java.util.UUID.randomUUID();
        jdbc.sql("INSERT INTO password_reset_tokens (user_id, token_hash, expires_at) VALUES (?, ?, DATE_ADD(CURRENT_TIMESTAMP, INTERVAL " + minutes + " MINUTE))")
                .params(userId, sha256(token)).update();
        return token;
    }

    @Test
    void theAnswerIsTheSameForAKnownAndAnUnknownAddress() throws Exception {
        String known = forgot("f1@test.edu", "10.0.0.1").andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String unknown = forgot("nobody@test.edu", "10.0.0.2").andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        assertEquals(known, unknown);
        forgot("", "10.0.0.3").andExpect(status().isAccepted());
        forgot("not an address at all", "10.0.0.4").andExpect(status().isAccepted());
    }

    @Test
    void aRequestStoresOnlyAHashOfARandomTokenAndNothingForAnUnknownAddress() throws Exception {
        forgot("nobody@test.edu", "10.0.1.1").andExpect(status().isAccepted());
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM password_reset_tokens").query(Integer.class).single());

        forgot("F1@TEST.EDU", "10.0.1.2").andExpect(status().isAccepted());
        Map<String, Object> row = jdbc.sql("SELECT user_id, token_hash, used_at, expires_at > CURRENT_TIMESTAMP AS live FROM password_reset_tokens")
                .query().singleRow();
        assertEquals(faculty.id(), ((Number) row.get("user_id")).longValue());
        assertEquals(64, row.get("token_hash").toString().length());
        assertTrue(row.get("token_hash").toString().matches("[0-9a-f]{64}"));
        assertEquals(null, row.get("used_at"));
        assertEquals(1, ((Number) row.get("live")).intValue());
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM audit_logs WHERE action = 'PASSWORD_RESET_REQUESTED'").query(Integer.class).single());
    }

    @Test
    void aNewRequestReplacesTheEarlierLink() throws Exception {
        String first = issueToken(faculty.id(), "30");
        forgot("f1@test.edu", "10.0.2.1").andExpect(status().isAccepted());
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM password_reset_tokens WHERE used_at IS NULL").query(Integer.class).single());
        send("/api/auth/reset-password", Map.of("token", first, "newPassword", "Quite-Long-Passphrase-77"), "10.0.2.2")
                .andExpect(status().isBadRequest());
    }

    @Test
    void theLinkLetsThePersonChooseTheirOwnPasswordOnceAndEndsTheirSessions() throws Exception {
        String token = issueToken(faculty.id(), "30");
        int before = jdbc.sql("SELECT session_version FROM users WHERE id = ?").param(faculty.id()).query(Integer.class).single();

        send("/api/auth/reset-password", Map.of("token", token, "newPassword", "Quite-Long-Passphrase-77"), "10.0.3.1")
                .andExpect(status().isOk());
        // the new password signs in; the old one no longer does
        send("/api/auth/login", Map.of("email", "f1@test.edu", "password", "Quite-Long-Passphrase-77"), "10.0.3.2").andExpect(status().isOk());
        send("/api/auth/login", Map.of("email", "f1@test.edu", "password", TestDb.PASSWORD), "10.0.3.3").andExpect(status().isUnauthorized());
        assertEquals(before + 1, jdbc.sql("SELECT session_version FROM users WHERE id = ?").param(faculty.id()).query(Integer.class).single());
        assertFalse(jdbc.sql("SELECT must_change_password FROM users WHERE id = ?").param(faculty.id()).query(Boolean.class).single());
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM audit_logs WHERE action = 'PASSWORD_RESET_BY_LINK'").query(Integer.class).single());

        // the same link does not work again
        send("/api/auth/reset-password", Map.of("token", token, "newPassword", "Another-Long-Passphrase-88"), "10.0.3.4")
                .andExpect(status().isBadRequest());
    }

    @Test
    void anExpiredOrUnknownLinkIsRefusedWithoutSayingWhich() throws Exception {
        String expired = issueToken(faculty.id(), "-5");
        send("/api/auth/reset-password", Map.of("token", expired, "newPassword", "Quite-Long-Passphrase-77"), "10.0.4.1")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("This link is not valid or has expired. Please ask for a new one."));
        send("/api/auth/reset-password", Map.of("token", "x".repeat(43), "newPassword", "Quite-Long-Passphrase-77"), "10.0.4.2")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("This link is not valid or has expired. Please ask for a new one."));
        send("/api/auth/reset-password", Map.of("newPassword", "Quite-Long-Passphrase-77"), "10.0.4.3").andExpect(status().isBadRequest());
    }

    @Test
    void theNewPasswordMustFollowTheRulesAndAWeakOneLeavesTheLinkUsable() throws Exception {
        String token = issueToken(faculty.id(), "30");
        send("/api/auth/reset-password", Map.of("token", token, "newPassword", "short"), "10.0.5.1")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.newPassword").exists());
        send("/api/auth/reset-password", Map.of("token", token, "newPassword", "Srivasavi@123"), "10.0.5.2")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.newPassword").exists());
        send("/api/auth/reset-password", Map.of("token", token, "newPassword", "Quite-Long-Passphrase-77"), "10.0.5.3").andExpect(status().isOk());
    }

    @Test
    void aDisabledAccountGetsNoLink() throws Exception {
        jdbc.sql("UPDATE users SET status = 'DISABLED' WHERE id = ?").param(faculty.id()).update();
        forgot("f1@test.edu", "10.0.6.1").andExpect(status().isAccepted());
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM password_reset_tokens").query(Integer.class).single());
        String token = issueToken(faculty.id(), "30");
        send("/api/auth/reset-password", Map.of("token", token, "newPassword", "Quite-Long-Passphrase-77"), "10.0.6.2").andExpect(status().isBadRequest());
    }

    @Test
    void requestsAreRateLimitedByEmailAndByAddress() throws Exception {
        for (int i = 0; i < 6; i++) forgot("f1@test.edu", "10.0.7." + (10 + i)).andExpect(status().isAccepted());
        // three requests were acted on; the rest were quietly dropped (a replaced link is marked used, so count all rows)
        assertEquals(3, jdbc.sql("SELECT COUNT(*) FROM password_reset_tokens").query(Integer.class).single());

        jdbc.sql("DELETE FROM password_reset_tokens").update();
        for (int i = 0; i < 12; i++) forgot("person" + i + "@test.edu", "10.0.8.1").andExpect(status().isAccepted());
        db.faculty("late@test.edu", "E777", "CSE", "ASST_PROF");
        forgot("late@test.edu", "10.0.8.1").andExpect(status().isAccepted());   // the address has used up its requests
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM password_reset_tokens").query(Integer.class).single());
    }
}
