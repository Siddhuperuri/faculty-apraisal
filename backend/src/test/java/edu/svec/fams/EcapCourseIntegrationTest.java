package edu.svec.fams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.svec.fams.appraisal.AppraisalService;
import edu.svec.fams.auth.FamsUserPrincipal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * A course's code and name are ECAP's, chosen from its list and never typed. The tests run against a stand-in ECAP (see
 * {@link TestEcapConfiguration}); how the real API's answers are read is configuration, not behaviour of this application.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EcapCourseIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestDb db;
    @Autowired AppraisalService appraisals;

    FamsUserPrincipal faculty;
    long id;

    @BeforeEach
    void setUp() {
        db.reset();
        faculty = db.faculty("f1@test.edu", "E001", "CSE", "ASST_PROF");
        id = appraisals.create(faculty);
        TestEcapConfiguration.DOWN.set(false);
    }

    @AfterEach
    void ecapIsBackUp() {
        TestEcapConfiguration.DOWN.set(false);
    }

    private Map<String, Object> course(String code, String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("courseCode", code);
        m.put("courseName", name);
        m.put("courseRole", "INSTRUCTOR");
        m.put("courseType", "THEORY");
        m.put("program", "B_TECH");
        m.put("branch", "CSE");
        m.put("semester", 3);
        m.put("section", "A");
        m.put("hoursPerWeek", 4);
        m.put("passPercentage", 90);
        return m;
    }

    private ResultActions save(List<Map<String, Object>> records) throws Exception {
        return mvc.perform(put("/api/appraisals/" + id + "/sections/teaching-courses").with(user(faculty)).with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("records", records))));
    }

    @Test
    void theCourseNameIsTakenFromEcapWhateverWasSent() throws Exception {
        save(List.of(course("CS301", "A name somebody typed"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].courseCode").value("CS301"))
                .andExpect(jsonPath("$.records[0].courseName").value("Course CS301"));
    }

    @Test
    void aCourseEcapDoesNotHaveIsRefusedWithAClearMessage() throws Exception {
        save(List.of(course("NOPE101", "Invented"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors['records[0].courseCode']").value("This course is not in ECAP. Choose it from the list of ECAP courses."));
    }

    @Test
    void whenEcapIsUnavailableANewCourseIsRefusedPlainlyButASavedOneCanBeSavedAgain() throws Exception {
        save(List.of(course("CS101", "x"))).andExpect(status().isOk());
        JsonNode saved = json.readTree(mvc.perform(get("/api/appraisals/" + id + "/sections/teaching-courses").with(user(faculty)))
                .andReturn().getResponse().getContentAsString());
        long recordId = saved.get("records").get(0).get("id").asLong();

        TestEcapConfiguration.DOWN.set(true);
        // a new course: 503 with a message a person can act on, and nothing changes
        save(List.of(course("CS101", "x"), course("CS102", "y"))).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("ECAP is not available right now. Please try again in a few minutes."));
        // the saved course, unchanged except for another field, still saves
        Map<String, Object> same = course("CS101", "Course CS101");
        same.put("id", recordId);
        same.put("hoursPerWeek", 5);
        save(List.of(same)).andExpect(status().isOk()).andExpect(jsonPath("$.records[0].hoursPerWeek").value(5));
        assertEquals(1, json.readTree(mvc.perform(get("/api/appraisals/" + id + "/sections/teaching-courses").with(user(faculty)))
                .andReturn().getResponse().getContentAsString()).get("records").size());
    }

    @Test
    void theSearchReturnsEcapCoursesAndSaysWhenEcapIsDown() throws Exception {
        mvc.perform(get("/api/ecap/courses").param("q", "cs3").with(user(faculty))).andExpect(status().isOk())
                .andExpect(jsonPath("$.courses[0].code").value("CS3"))
                .andExpect(jsonPath("$.courses[0].name").value("Course CS3"));
        mvc.perform(get("/api/ecap/courses").param("q", "c").with(user(faculty))).andExpect(status().isOk())
                .andExpect(jsonPath("$.courses.length()").value(0));                      // too short to search
        mvc.perform(get("/api/ecap/courses").param("q", "NOPE").with(user(faculty))).andExpect(status().isOk())
                .andExpect(jsonPath("$.courses.length()").value(0));
        TestEcapConfiguration.DOWN.set(true);
        mvc.perform(get("/api/ecap/courses").param("q", "cs3").with(user(faculty))).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("ECAP is not available right now. Please try again in a few minutes."));
    }

    @Test
    void theSearchNeedsASignedInUser() throws Exception {
        mvc.perform(get("/api/ecap/courses").param("q", "cs3")).andExpect(status().isUnauthorized());
    }
}
