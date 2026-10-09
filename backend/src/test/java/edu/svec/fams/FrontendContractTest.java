package edu.svec.fams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.svec.fams.auth.FamsUserPrincipal;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Keeps the frontend honest. The UI's contract test checks its screens against a snapshot of
 * GET /api/sections/meta (frontend/src/lib/__fixtures__/meta.json). This test fails if that snapshot differs
 * from what the server really serves. After an intentional change, regenerate it with
 * {@code mvn test -Dtest=FrontendContractTest -Dfams.updateFixtures=true} and run the frontend tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FrontendContractTest {

    private static final Path FIXTURE = Path.of("..", "frontend", "src", "lib", "__fixtures__", "meta.json");

    @Autowired MockMvc mvc;
    @Autowired TestDb db;
    @Autowired ObjectMapper json;

    @Test
    void theFrontendSnapshotMatchesTheServersFieldDefinitions() throws Exception {
        db.reset();
        FamsUserPrincipal faculty = db.faculty("f1@test.edu", "E001", "CSE", "ASST_PROF");
        String body = mvc.perform(get("/api/sections/meta").with(user(faculty))).andReturn().getResponse().getContentAsString();
        JsonNode live = json.readTree(body);

        if (Boolean.getBoolean("fams.updateFixtures")) {
            Files.createDirectories(FIXTURE.getParent());
            Files.writeString(FIXTURE, json.writerWithDefaultPrettyPrinter().writeValueAsString(live) + "\n");
        }

        assertTrue(Files.exists(FIXTURE), "snapshot missing; regenerate with -Dfams.updateFixtures=true");
        JsonNode snapshot = json.readTree(Files.readString(FIXTURE));
        assertEquals(snapshot, live, "frontend/src/lib/__fixtures__/meta.json is out of date. Regenerate it "
                + "(mvn test -Dtest=FrontendContractTest -Dfams.updateFixtures=true) and re-run the frontend tests.");
    }
}
