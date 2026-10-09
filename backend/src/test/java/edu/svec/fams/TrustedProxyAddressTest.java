package edu.svec.fams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/**
 * Behind a reverse proxy (FAMS_FORWARD_HEADERS=native) the client address comes from X-Forwarded-For. A proxy usually
 * appends the address it saw to whatever the client sent, so the header reads "what the client claimed, real address".
 * Only the part the proxy wrote may count. This test is the proxy: it runs on this machine, which is the default trusted
 * proxy address.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "server.forward-headers-strategy=native")
@ActiveProfiles("test")
class TrustedProxyAddressTest {

    @LocalServerPort int port;
    @Autowired TestDb db;

    final HttpClient http = HttpClient.newHttpClient();
    String cookie;
    String token;

    @BeforeEach
    void setUp() throws Exception {
        db.reset();
        HttpResponse<String> csrf = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/csrf")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        cookie = csrf.headers().allValues("Set-Cookie").stream().filter(c -> c.startsWith("XSRF-TOKEN=")).findFirst().orElseThrow().split(";")[0];
        Matcher m = Pattern.compile("\"token\":\"([^\"]+)\"").matcher(csrf.body());
        assertTrue(m.find(), csrf.body());
        token = m.group(1);
    }

    private int guess(String email, String forwardedFor) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/login"))
                .header("Cookie", cookie).header("X-XSRF-TOKEN", token).header("Content-Type", "application/json")
                .header("X-Forwarded-For", forwardedFor)
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"" + email + "\",\"password\":\"wrong\"}")).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    @Test
    void theAddressTheClientClaimsIsNotBelievedOnlyTheOneTheProxyAdded() throws Exception {
        String email = "victim-" + UUID.randomUUID() + "@test.edu";
        // One real client (10.20.30.40, written by the proxy) pretending to be a different one each time.
        for (int i = 0; i < 5; i++) assertEquals(401, guess(email, "198.51.100." + i + ", 10.20.30.40"));
        assertEquals(429, guess(email, "198.51.100.200, 10.20.30.40"), "a new claimed address must not reset the limit");
    }

    @Test
    void differentRealClientsBehindTheProxyAreCountedSeparately() throws Exception {
        String email = "victim-" + UUID.randomUUID() + "@test.edu";
        for (int i = 0; i < 5; i++) assertEquals(401, guess(email, "10.20.31.1"));
        assertEquals(429, guess(email, "10.20.31.1"));
        // Someone at another machine is not shut out by that (the whole point of forwarding the address).
        assertEquals(401, guess(email, "10.20.31.2"));
    }
}
