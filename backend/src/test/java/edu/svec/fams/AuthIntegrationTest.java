package edu.svec.fams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import edu.svec.fams.auth.FamsUserPrincipal;
import edu.svec.fams.auth.Role;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired TestDb db;

    @BeforeEach
    void seed() {
        db.reset();
        db.user("faculty@test.edu", Role.FACULTY);
        db.user("hod@test.edu", Role.HOD);
    }

    private static String loginJson(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    private MvcResult tryLogin(String email, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON).content(loginJson(email, password))).andReturn();
    }

    private MockHttpSession login(String email) throws Exception {
        MvcResult r = mvc.perform(post("/api/auth/login").with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON).content(loginJson(email, TestDb.PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andReturn();
        return (MockHttpSession) r.getRequest().getSession(false);
    }

    @Test
    void validLoginReturnsRoleAndEstablishesSession() throws Exception {
        MockHttpSession session = login("faculty@test.edu");
        mvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("FACULTY"));
    }

    @Test
    void emailIsCaseInsensitiveAndTrimmed() throws Exception {
        assertEquals(200, tryLogin("  Faculty@TEST.edu ", TestDb.PASSWORD).getResponse().getStatus());
    }

    @Test
    void responsesNeverExposeThePasswordHash() throws Exception {
        MvcResult login = mvc.perform(post("/api/auth/login").with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON).content(loginJson("faculty@test.edu", TestDb.PASSWORD)))
                .andReturn();
        String body = login.getResponse().getContentAsString();
        assertFalse(body.replace("mustChangePassword", "").toLowerCase().contains("password"), body);   // the flag's name is not a secret
        assertFalse(body.contains("$2a$"), body);
        String me = mvc.perform(get("/api/auth/me").session((MockHttpSession) login.getRequest().getSession(false)))
                .andReturn().getResponse().getContentAsString();
        assertFalse(me.replace("mustChangePassword", "").toLowerCase().contains("password"), me);
    }

    @Test
    void sessionHoldsNoPasswordHash() throws Exception {
        MockHttpSession session = login("faculty@test.edu");
        Object ctx = session.getAttribute("SPRING_SECURITY_CONTEXT");
        org.springframework.security.core.context.SecurityContext sc =
                (org.springframework.security.core.context.SecurityContext) ctx;
        FamsUserPrincipal p = (FamsUserPrincipal) sc.getAuthentication().getPrincipal();
        org.junit.jupiter.api.Assertions.assertNull(p.getPassword());
    }

    @Test
    void wrongPasswordUnknownUserAndDisabledAccountGiveIdenticalResponse() throws Exception {
        FamsUserPrincipal disabled = db.user("off@test.edu", Role.FACULTY);
        db.disable(disabled);
        String wrong = tryLogin("faculty@test.edu", "nope").getResponse().getContentAsString();
        String unknown = tryLogin("ghost@test.edu", "nope").getResponse().getContentAsString();
        MvcResult off = tryLogin("off@test.edu", TestDb.PASSWORD);
        assertEquals(401, off.getResponse().getStatus());
        assertEquals(wrong, unknown);
        assertEquals(wrong, off.getResponse().getContentAsString());
    }

    @Test
    void overlongPasswordIsRejectedAsBadCredentialsNotAServerError() throws Exception {
        assertEquals(401, tryLogin("faculty@test.edu", "x".repeat(150)).getResponse().getStatus());
    }

    @Test
    void blankFieldsGiveFieldLevelErrors() throws Exception {
        mvc.perform(post("/api/auth/login").with(csrf().asHeader()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.email").exists())
                .andExpect(jsonPath("$.fieldErrors.password").exists());
    }

    @Test
    void malformedJsonIsAClientErrorNotAServerError() throws Exception {
        mvc.perform(post("/api/auth/login").with(csrf().asHeader()).contentType(MediaType.APPLICATION_JSON).content("{nope"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void unauthenticatedRequestIsRejected() throws Exception {
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/appraisals/1")).andExpect(status().isUnauthorized());
    }

    @Test
    void loginWithoutCsrfTokenIsRejected() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("faculty@test.edu", TestDb.PASSWORD)))
                .andExpect(status().isForbidden());
    }

    @Test
    void facultyCannotReachHodPrincipalOrAdminAreas() throws Exception {
        MockHttpSession session = login("faculty@test.edu");
        for (String path : new String[] {"/api/hod/x", "/api/principal/x", "/api/admin/x"}) {
            mvc.perform(get(path).session(session)).andExpect(status().isForbidden());
        }
    }

    @Test
    void logoutEndsTheSession() throws Exception {
        MockHttpSession session = login("faculty@test.edu");
        mvc.perform(post("/api/auth/logout").with(csrf().asHeader()).session(session)).andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void sessionIdChangesOnLoginToPreventFixation() throws Exception {
        MockHttpSession preLogin = new MockHttpSession();
        String before = preLogin.getId();
        MvcResult r = mvc.perform(post("/api/auth/login").with(csrf().asHeader()).session(preLogin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginJson("faculty@test.edu", TestDb.PASSWORD))).andReturn();
        assertNotEquals(before, r.getRequest().getSession(false).getId());
    }

    @Test
    void securityHeadersAreSetAndRequestIdIsEchoed() throws Exception {
        mvc.perform(get("/api/auth/me").header("X-Request-Id", "abcd1234-test"))
                .andExpect(header().string("X-Request-Id", "abcd1234-test"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'"));
    }

    @Test
    void unsafeRequestIdIsReplacedNotEchoed() throws Exception {
        MvcResult r = mvc.perform(get("/api/auth/me").header("X-Request-Id", "bad id\twith junk")).andReturn();
        String echoed = r.getResponse().getHeader("X-Request-Id");
        assertFalse(echoed.contains(" "));
        UUID.fromString(echoed); // generated UUID
    }

    /** The college chose not to limit sign-in attempts: a wrong password never locks an account or an address. */
    @Test
    void signInIsNeverRefusedHoweverManyWrongPasswordsWereTried() throws Exception {
        String email = "victim-" + UUID.randomUUID() + "@test.edu";
        db.user(email, Role.FACULTY);
        for (int i = 0; i < 12; i++) assertEquals(401, tryLogin(email, "bad-" + i).getResponse().getStatus());
        assertEquals(200, tryLogin(email, TestDb.PASSWORD).getResponse().getStatus());
    }

    /**
     * The database compares e-mail addresses without regard to accents or letter width, so a look-alike spelling finds
     * the real account. If a look-alike could sign in, one account would have several spellings. Only the address as it is
     * stored (in any letter case) is the account.
     */
    @Test
    void aLookAlikeSpellingOfAnAddressIsNotThatAccount() throws Exception {
        db.user("principal@test.edu", Role.PRINCIPAL);
        String[] lookAlikes = {
            "pr\u00EDncipal@test.edu",      // i with an acute accent
            "pr\u00ECncipal@test.edu",      // i with a grave accent
            "\uFF50rincipal@test.edu",      // full-width p
            "principal@t\u00E9st.edu",      // accent in the domain
            "principal@te\u017Ft.edu",      // long s, which equalsIgnoreCase treats as "s"
            "principal@test.edu\u0301",     // a combining accent on the end
        };
        for (String lookAlike : lookAlikes) {
            assertEquals(401, tryLogin(lookAlike, TestDb.PASSWORD).getResponse().getStatus(), lookAlike + " must not sign in");
        }
        assertEquals(200, tryLogin("PRINCIPAL@Test.edu", TestDb.PASSWORD).getResponse().getStatus());   // letter case still does not matter
    }

    @Test
    void aRefusedRequestDoesNotOpenAServerSideSession() throws Exception {
        // Otherwise anyone could fill the server's memory with sessions just by asking for things.
        for (String path : new String[] {"/api/auth/me", "/api/appraisals/1", "/api/admin/users", "/api/nope"}) {
            MvcResult r = mvc.perform(get(path)).andExpect(status().isUnauthorized()).andReturn();
            org.junit.jupiter.api.Assertions.assertNull(r.getRequest().getSession(false), path);
        }
        MvcResult failed = tryLogin("ghost-" + UUID.randomUUID() + "@test.edu", "nope");
        assertEquals(401, failed.getResponse().getStatus());
        org.junit.jupiter.api.Assertions.assertNull(failed.getRequest().getSession(false));
    }

    @Test
    void aBodyLargerThanTheLimitIsRefusedBeforeItIsRead() throws Exception {
        String huge = "{\"email\":\"" + "a".repeat(1_100_000) + "@test.edu\",\"password\":\"x\"}";
        mvc.perform(post("/api/auth/login").with(csrf().asHeader()).contentType(MediaType.APPLICATION_JSON).content(huge))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.message").value("The request is too large."));
        // Just under the limit is read normally (and refused for what it says, not for its size).
        String large = "{\"email\":\"" + "a".repeat(900_000) + "@test.edu\",\"password\":\"x\"}";
        mvc.perform(post("/api/auth/login").with(csrf().asHeader()).contentType(MediaType.APPLICATION_JSON).content(large))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anotherOriginIsRefusedBecauseNoneIsConfigured() throws Exception {
        // Pages and API share an origin, so by default no other site may call the API from a browser.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options("/api/auth/login")
                        .header("Origin", "https://evil.example").header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        mvc.perform(get("/api/auth/csrf").header("Origin", "https://evil.example"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
        // The origin the development set-up used to allow is no longer special either.
        mvc.perform(get("/api/auth/csrf").header("Origin", "http://localhost:3000"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void crossOriginIsolationHeadersAreSet() throws Exception {
        mvc.perform(get("/api/auth/me"))
                .andExpect(header().string("Cross-Origin-Resource-Policy", "same-origin"))
                .andExpect(header().string("Cross-Origin-Opener-Policy", "same-origin"));
    }
}
