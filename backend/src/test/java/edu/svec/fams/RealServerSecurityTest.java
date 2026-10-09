package edu.svec.fams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.svec.fams.auth.Role;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/**
 * Talks to a real embedded server over HTTP, so cookie attributes and the CSRF handshake are exactly
 * what a browser would see (MockMvc cannot show these faithfully).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RealServerSecurityTest {

    @LocalServerPort int port;
    @Autowired TestDb db;

    final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() {
        db.reset();
        db.user("faculty@test.edu", Role.FACULTY);
    }

    private HttpResponse<String> send(HttpRequest.Builder b) throws Exception {
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder req(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    }

    private static String cookieHeader(List<String> setCookies, String name) {
        return setCookies.stream().filter(c -> c.startsWith(name + "=")).findFirst().orElse(null);
    }

    private static String token(String json) {
        Matcher m = Pattern.compile("\"token\":\"([^\"]+)\"").matcher(json);
        assertTrue(m.find(), json);
        return m.group(1);
    }

    @Test
    void csrfCookieIsScriptReadableAndSameSiteLax() throws Exception {
        HttpResponse<String> r = send(req("/api/auth/csrf").GET());
        String c = cookieHeader(r.headers().allValues("Set-Cookie"), "XSRF-TOKEN");
        assertTrue(c != null, "XSRF-TOKEN cookie must be issued: " + r.headers());
        assertTrue(c.contains("SameSite=Lax"), c);
        assertTrue(c.contains("Secure"), "the CSRF cookie must be Secure by default: " + c);
        assertFalse(c.contains("HttpOnly"), "the SPA must be able to read it: " + c);
        assertTrue(c.contains("Path=/"), c);
    }

    @Test
    void fullLoginHandshakeWorksAndSessionCookieIsHardened() throws Exception {
        HttpResponse<String> csrf = send(req("/api/auth/csrf").GET());
        String token = token(csrf.body());
        String xsrfCookie = cookieHeader(csrf.headers().allValues("Set-Cookie"), "XSRF-TOKEN").split(";")[0];
        String body = "{\"email\":\"faculty@test.edu\",\"password\":\"" + TestDb.PASSWORD + "\"}";

        // Without the CSRF header the login is refused even with the cookie present.
        HttpResponse<String> noHeader = send(req("/api/auth/login").header("Cookie", xsrfCookie)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)));
        assertEquals(403, noHeader.statusCode(), noHeader.body() + " " + noHeader.headers());

        HttpResponse<String> ok = send(req("/api/auth/login").header("Cookie", xsrfCookie)
                .header("X-XSRF-TOKEN", token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
        assertEquals(200, ok.statusCode(), ok.body());

        String session = cookieHeader(ok.headers().allValues("Set-Cookie"), "JSESSIONID");
        assertTrue(session != null, "session cookie expected: " + ok.headers());
        assertTrue(session.contains("HttpOnly"), session);
        assertTrue(session.contains("SameSite=Lax"), session);
        // No profile is active here beyond "test", so this is the production default: HTTPS only.
        assertTrue(session.contains("Secure"), "the session cookie must be Secure by default: " + session);

        HttpResponse<String> me = send(req("/api/auth/me").header("Cookie", session.split(";")[0] + "; " + xsrfCookie).GET());
        assertEquals(200, me.statusCode());
        assertTrue(me.body().contains("FACULTY"), me.body());
    }

    @Test
    void signingInRetiresTheCsrfTokenIssuedBeforeIt() throws Exception {
        HttpResponse<String> csrf = send(req("/api/auth/csrf").GET());
        String xsrfCookie = cookieHeader(csrf.headers().allValues("Set-Cookie"), "XSRF-TOKEN").split(";")[0];
        HttpResponse<String> ok = send(req("/api/auth/login").header("Cookie", xsrfCookie)
                .header("X-XSRF-TOKEN", token(csrf.body())).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"faculty@test.edu\",\"password\":\"" + TestDb.PASSWORD + "\"}")));
        assertEquals(200, ok.statusCode(), ok.body());

        // The pre-sign-in token's cookie is expired by the sign-in response; the page fetches a fresh one before its next write.
        String cleared = cookieHeader(ok.headers().allValues("Set-Cookie"), "XSRF-TOKEN");
        assertTrue(cleared != null && cleared.startsWith("XSRF-TOKEN=;") && cleared.contains("Max-Age=0"), "expected the cookie to be cleared: " + cleared);

        String session = cookieHeader(ok.headers().allValues("Set-Cookie"), "JSESSIONID").split(";")[0];
        HttpResponse<String> fresh = send(req("/api/auth/csrf").header("Cookie", session).GET());
        String next = cookieHeader(fresh.headers().allValues("Set-Cookie"), "XSRF-TOKEN");
        assertTrue(next != null && !next.split(";")[0].equals(xsrfCookie), "a new token is issued after sign-in: " + next);
    }

    /**
     * A page on another site of the same parent domain can plant the cookie and submit a form with a matching
     * parameter, and the browser sends the session with it. It cannot set a header, so only the header may count.
     */
    @Test
    void theCsrfTokenCountsOnlyInTheHeaderNeverAsAParameter() throws Exception {
        HttpResponse<String> csrf = send(req("/api/auth/csrf").GET());
        String token = token(csrf.body());
        String xsrfCookie = cookieHeader(csrf.headers().allValues("Set-Cookie"), "XSRF-TOKEN").split(";")[0];
        String body = "{\"email\":\"faculty@test.edu\",\"password\":\"" + TestDb.PASSWORD + "\"}";

        HttpResponse<String> inQuery = send(req("/api/auth/login?_csrf=" + token).header("Cookie", xsrfCookie)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)));
        assertEquals(403, inQuery.statusCode(), inQuery.body());

        // An endpoint that takes no body is what a plain form could reach. Refused for the token (403), which comes
        // before "not signed in" (401): had the parameter been accepted, this would be 401.
        HttpResponse<String> inForm = send(req("/api/auth/logout").header("Cookie", xsrfCookie)
                .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString("_csrf=" + token)));
        assertEquals(403, inForm.statusCode(), inForm.body());

        HttpResponse<String> inHeader = send(req("/api/auth/logout").header("Cookie", xsrfCookie)
                .header("X-XSRF-TOKEN", token).POST(HttpRequest.BodyPublishers.noBody()));
        assertEquals(401, inHeader.statusCode(), inHeader.body());
    }

    @Test
    void anonymousRequestsAreNotGivenASession() throws Exception {
        for (String path : new String[] {"/api/auth/me", "/api/appraisals", "/api/admin/users", "/api/auth/csrf", "/actuator/health"}) {
            HttpResponse<String> r = send(req(path).GET());
            assertTrue(cookieHeader(r.headers().allValues("Set-Cookie"), "JSESSIONID") == null,
                    path + " must not create a session for someone who is not signed in: " + r.headers());
        }
    }

    @Test
    void aBodyWithNoDeclaredLengthIsCutOffAtTheLimit() throws Exception {
        HttpResponse<String> csrf = send(req("/api/auth/csrf").GET());
        String xsrfCookie = cookieHeader(csrf.headers().allValues("Set-Cookie"), "XSRF-TOKEN").split(";")[0];
        byte[] body = ("{\"email\":\"" + "a".repeat(1_200_000) + "@test.edu\",\"password\":\"x\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        // A stream publisher sends the body chunked, with no Content-Length for the server to check up front.
        HttpResponse<String> r = send(req("/api/auth/login").header("Cookie", xsrfCookie)
                .header("X-XSRF-TOKEN", token(csrf.body())).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream(body))));
        assertEquals(413, r.statusCode(), r.body());
        assertTrue(r.body().contains("too large"), r.body());
    }

    @Test
    void aForwardingHeaderFromTheClientIsIgnoredUnlessAProxyIsConfigured() throws Exception {
        HttpResponse<String> csrf = send(req("/api/auth/csrf").GET());
        String xsrfCookie = cookieHeader(csrf.headers().allValues("Set-Cookie"), "XSRF-TOKEN").split(";")[0];
        String token = token(csrf.body());
        String body = "{\"email\":\"spoof-" + java.util.UUID.randomUUID() + "@test.edu\",\"password\":\"wrong\"}";
        // Sign-in attempts are not limited, so the sixth wrong password is answered like the first.
        for (int i = 0; i < 5; i++) {
            HttpResponse<String> r = send(req("/api/auth/login").header("Cookie", xsrfCookie).header("X-XSRF-TOKEN", token)
                    .header("X-Forwarded-For", "203.0.113." + i).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)));
            assertEquals(401, r.statusCode(), r.body());
        }
        HttpResponse<String> sixth = send(req("/api/auth/login").header("Cookie", xsrfCookie).header("X-XSRF-TOKEN", token)
                .header("X-Forwarded-For", "203.0.113.99").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
        assertEquals(401, sixth.statusCode(), sixth.body());
    }

    @Test
    void healthEndpointIsPublicAndLeaksNothing() throws Exception {
        HttpResponse<String> r = send(req("/actuator/health").GET());
        assertEquals(200, r.statusCode());
        assertEquals("{\"status\":\"UP\"}", r.body());
    }

    @Test
    void otherActuatorEndpointsAreNotExposed() throws Exception {
        assertEquals(401, send(req("/actuator/env").GET()).statusCode());
        assertEquals(401, send(req("/actuator/beans").GET()).statusCode());
    }

    @Test
    void unknownApiPathIsRejectedWithoutALoginAndNeverShowsAStackTrace() throws Exception {
        HttpResponse<String> r = send(req("/api/nope").GET());
        assertEquals(401, r.statusCode());
        assertFalse(r.body().contains("Exception"), r.body());
    }
}
