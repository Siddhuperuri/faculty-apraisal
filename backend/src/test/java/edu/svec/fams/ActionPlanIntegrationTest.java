package edu.svec.fams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.svec.fams.appraisal.AppraisalService;
import edu.svec.fams.auth.FamsUserPrincipal;
import edu.svec.fams.auth.Role;
import edu.svec.fams.section.FieldSpec;
import edu.svec.fams.section.Sections;
import java.util.List;
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
 * Section 12, "Action Plan for the Next 2 Years": one text of at most 2,000 words (words, not characters). It may be empty
 * in a draft but is required before the appraisal is submitted, is kept with the appraisal, and goes on the report.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ActionPlanIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestDb db;
    @Autowired JdbcClient jdbc;
    @Autowired AppraisalService appraisals;

    FamsUserPrincipal faculty, other, hod;
    long id;

    @BeforeEach
    void setUp() {
        db.reset();
        faculty = db.faculty("f1@test.edu", "E001", "CSE", "ASST_PROF");
        other = db.faculty("f2@test.edu", "E002", "CSE", "PROFESSOR");
        hod = db.hod("hod@test.edu", "CSE");
        id = appraisals.create(faculty);
    }

    private static String words(int n) {
        return "word ".repeat(n).strip();
    }

    private ResultActions save(FamsUserPrincipal who, String plan) throws Exception {
        Map<String, Object> record = new java.util.LinkedHashMap<>();
        record.put("plan", plan);
        return mvc.perform(put("/api/appraisals/" + id + "/sections/action-plan").with(user(who)).with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("records", List.of(record)))));
    }

    private ResultActions view(FamsUserPrincipal who) throws Exception {
        return mvc.perform(get("/api/appraisals/" + id).with(user(who)));
    }

    @Test
    void itIsTheLastSectionOfTheFormAndDescribedWithItsWordLimit() throws Exception {
        List<String> keys = List.copyOf(Sections.all().keySet());
        assertEquals("action-plan", keys.get(keys.size() - 1));
        JsonNode meta = json.readTree(mvc.perform(get("/api/sections/meta").with(user(faculty))).andReturn().getResponse().getContentAsString());
        JsonNode plan = null;
        for (JsonNode s : meta) {
            if (s.get("key").asText().equals("action-plan")) plan = s;
        }
        assertTrue(plan != null && plan.get("singleton").asBoolean());
        JsonNode field = plan.get("fields").get(0);
        assertEquals("plan", field.get("name").asText());
        assertEquals(2000, field.get("maxWords").asInt());
        assertTrue(field.get("submitRequired").asBoolean());
        assertFalse(field.get("required").asBoolean());   // a draft may leave it empty
    }

    @Test
    void wordsAreCountedNotCharacters() {
        assertEquals(0, FieldSpec.countWords(null));
        assertEquals(0, FieldSpec.countWords("   \n\t "));
        assertEquals(1, FieldSpec.countWords("plan"));
        assertEquals(5, FieldSpec.countWords("  one two\nthree\t four\r\nfive  "));
        assertEquals(2000, FieldSpec.countWords(words(2000)));
        assertEquals(3, FieldSpec.countWords("well-known state-of-the-art plan"));
    }

    @Test
    void aDraftMayBeSavedEmptyAndThenWithAPlan() throws Exception {
        mvc.perform(put("/api/appraisals/" + id + "/sections/action-plan").with(user(faculty)).with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON).content("{\"records\":[]}")).andExpect(status().isOk());
        save(faculty, "Mentor more students.\nPublish two papers a year.").andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].plan").value("Mentor more students.\nPublish two papers a year."));
        assertEquals("Mentor more students.\nPublish two papers a year.",
                jdbc.sql("SELECT plan_text FROM action_plan WHERE appraisal_id = ?").param(id).query(String.class).single());
    }

    @Test
    void exactly2000WordsIsAcceptedAndTheNextWordIsRefusedWithTheCount() throws Exception {
        save(faculty, words(2000)).andExpect(status().isOk());
        save(faculty, words(2001)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors['records[0].plan']").value("Action plan must be at most 2,000 words (it has 2,001)."));
        // the refused text changed nothing
        assertEquals(2000, FieldSpec.countWords(jdbc.sql("SELECT plan_text FROM action_plan WHERE appraisal_id = ?").param(id)
                .query(String.class).single()));
        // long words and line breaks are still counted as words, not as characters
        save(faculty, ("internationalisation\n").repeat(1999)).andExpect(status().isOk());
    }

    @Test
    void itIsRequiredForSubmissionAndOnlyAnEmptyOrBlankPlanBlocks() throws Exception {
        db.complete(id);
        jdbc.sql("DELETE FROM action_plan WHERE appraisal_id = ?").param(id).update();

        view(faculty).andExpect(jsonPath("$.submitBlockers.length()").value(1))
                .andExpect(jsonPath("$.submitBlockers[0]").value("Action Plan for the Next 2 Years: write your plan (at most 2,000 words)"));
        mvc.perform(post("/api/appraisals/" + id + "/submit").with(user(faculty)).with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON).content("{\"declarationAccepted\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Action Plan for the Next 2 Years")));

        // a plan made of nothing but spaces is not a plan; the framework removes a blank record, a stray row is still refused
        jdbc.sql("INSERT INTO action_plan (appraisal_id, plan_text) VALUES (?, ' \n ')").param(id).update();
        view(faculty).andExpect(jsonPath("$.submitBlockers.length()").value(1));

        save(faculty, "A real plan.").andExpect(status().isOk());
        view(faculty).andExpect(jsonPath("$.submitBlockers.length()").value(0));
        mvc.perform(post("/api/appraisals/" + id + "/submit").with(user(faculty)).with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON).content("{\"declarationAccepted\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUBMITTED"));
    }

    @Test
    void aSubmittedPlanCannotBeChangedAndIsReadableByTheHodButNotByOtherFaculty() throws Exception {
        db.complete(id);
        mvc.perform(post("/api/appraisals/" + id + "/submit").with(user(faculty)).with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON).content("{\"declarationAccepted\":true}")).andExpect(status().isOk());
        save(faculty, "Changed after submission").andExpect(status().is4xxClientError());

        mvc.perform(get("/api/appraisals/" + id + "/sections/action-plan").with(user(hod))).andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].plan").value(org.hamcrest.Matchers.startsWith("Strengthen teaching")));
        mvc.perform(get("/api/appraisals/" + id + "/sections/action-plan").with(user(other))).andExpect(status().isNotFound());
    }

    @Test
    void theSavedPlanIsAuditedAsASectionSaveWithoutItsText() throws Exception {
        save(faculty, "The plan itself is private text and is not copied into the audit trail.").andExpect(status().isOk());
        String meta = jdbc.sql("SELECT CAST(metadata AS CHAR) FROM audit_logs WHERE action = 'SECTION_SAVED' ORDER BY id DESC LIMIT 1")
                .query(String.class).single();
        assertTrue(meta.contains("action-plan"), meta);
        assertFalse(meta.contains("private text"), meta);
    }

    @Test
    void anAdministratorCannotWriteSomeoneElsesPlan() throws Exception {
        FamsUserPrincipal admin = db.user("admin@test.edu", Role.ADMIN);
        save(admin, "Not mine").andExpect(status().is4xxClientError());
    }
}
