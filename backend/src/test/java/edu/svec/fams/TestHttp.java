package edu.svec.fams;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Test helper: sign in through the real login endpoint and make session-bound JSON calls. */
final class TestHttp {
    private final MockMvc mvc;
    private final ObjectMapper json;

    TestHttp(MockMvc mvc, ObjectMapper json) {
        this.mvc = mvc;
        this.json = json;
    }

    MvcResult tryLogin(String email, String password) throws Exception {
        return mvc.perform(request(HttpMethod.POST, "/api/auth/login").with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("email", email, "password", password)))).andReturn();
    }

    /** Signs in and returns the session; fails the test when sign-in is refused. */
    MockHttpSession login(String email, String password) throws Exception {
        MvcResult r = tryLogin(email, password);
        if (r.getResponse().getStatus() != 200) {
            throw new AssertionError("Login for " + email + " returned " + r.getResponse().getStatus()
                    + ": " + r.getResponse().getContentAsString());
        }
        return (MockHttpSession) r.getRequest().getSession(false);
    }

    MockHttpSession login(String email) throws Exception { return login(email, TestDb.PASSWORD); }

    ResultActions call(MockHttpSession session, HttpMethod method, String path, Object body) throws Exception {
        MockHttpServletRequestBuilder req = request(method, path).with(csrf().asHeader());
        if (session != null) req = req.session(session);
        if (body != null) {
            req = req.contentType(MediaType.APPLICATION_JSON)
                    .content(body instanceof String s ? s : json.writeValueAsString(body));
        }
        return mvc.perform(req);
    }

    /** Sends {@code bytes} as the body of a file upload (a CSV file), as the browser does. */
    ResultActions postFile(MockHttpSession session, String path, byte[] bytes) throws Exception {
        MockHttpServletRequestBuilder req = request(HttpMethod.POST, path).with(csrf().asHeader())
                .contentType("text/csv").content(bytes);
        if (session != null) req = req.session(session);
        return mvc.perform(req);
    }

    ResultActions get(MockHttpSession session, String path) throws Exception { return call(session, HttpMethod.GET, path, null); }

    ResultActions post(MockHttpSession session, String path, Object body) throws Exception {
        return call(session, HttpMethod.POST, path, body);
    }

    ResultActions put(MockHttpSession session, String path, Object body) throws Exception {
        return call(session, HttpMethod.PUT, path, body);
    }

    JsonNode read(ResultActions actions) throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsString());
    }
}
