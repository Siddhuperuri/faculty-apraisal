package edu.svec.fams.ecap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads courses from the ECAP HTTP API. Everything about its shape is configuration (see {@link EcapConfig}), because
 * it belongs to the college's system, not to this application: the base address, the key and the header it travels in,
 * the paths for searching and for one course, and the names of the code and name fields in its JSON. The key is sent
 * only from the server; the browser never sees it.
 */
final class HttpEcapClient implements EcapClient {

    private static final Logger log = LoggerFactory.getLogger(HttpEcapClient.class);
    private static final String UNAVAILABLE = "ECAP is not available right now. Please try again in a few minutes.";

    record Settings(String baseUrl, String apiKey, String keyHeader, String searchPath, String codePath, String listField,
                    String codeField, String nameField, int timeoutSeconds) {}

    private final Settings s;
    private final ObjectMapper json;
    private final HttpClient http;

    HttpEcapClient(Settings settings, ObjectMapper json) {
        this.s = settings;
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(settings.timeoutSeconds())).build();
    }

    @Override
    public List<EcapCourse> search(String query, int limit) {
        JsonNode body = get(s.searchPath().replace("{q}", URLEncoder.encode(query, StandardCharsets.UTF_8)));
        JsonNode list = s.listField().isEmpty() ? body : body.path(s.listField());
        List<EcapCourse> out = new ArrayList<>();
        if (list.isArray()) {
            for (JsonNode n : list) {
                EcapCourse c = course(n);
                if (c != null && out.size() < limit) out.add(c);
            }
        }
        return out;
    }

    @Override
    public Optional<EcapCourse> find(String code) {
        JsonNode body = get(s.codePath().replace("{code}", URLEncoder.encode(code, StandardCharsets.UTF_8)));
        if (body == null) return Optional.empty();
        JsonNode node = body;
        if (!s.listField().isEmpty() && body.has(s.listField())) node = body.get(s.listField());
        if (node.isArray()) node = node.isEmpty() ? null : node.get(0);
        EcapCourse c = node == null ? null : course(node);
        return c != null && c.code().equalsIgnoreCase(code) ? Optional.of(c) : Optional.empty();
    }

    private EcapCourse course(JsonNode n) {
        String code = n.path(s.codeField()).asText("").strip();
        String name = n.path(s.nameField()).asText("").strip();
        return code.isEmpty() || name.isEmpty() ? null : new EcapCourse(code, name);
    }

    /** The parsed JSON of a path; null when ECAP says it has no such thing (404). */
    private JsonNode get(String path) {
        try {
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(s.baseUrl() + path))
                    .timeout(Duration.ofSeconds(s.timeoutSeconds())).header("Accept", "application/json").GET();
            if (!s.apiKey().isEmpty()) req.header(s.keyHeader(), s.apiKey());
            HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 404) return null;
            if (res.statusCode() / 100 != 2) {
                log.warn("ECAP answered {}", res.statusCode());
                throw new EcapUnavailableException(UNAVAILABLE);
            }
            return json.readTree(res.body());
        } catch (IOException e) {
            log.warn("ECAP could not be read: {}", e.getClass().getSimpleName());
            throw new EcapUnavailableException(UNAVAILABLE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EcapUnavailableException(UNAVAILABLE);
        } catch (IllegalArgumentException e) {
            throw new EcapUnavailableException("ECAP is not configured correctly. Please tell your administrator.");
        }
    }
}
