package edu.svec.fams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
 * A course's name and code are typed by the faculty member, and ECAP is never asked while a course is entered or saved (the
 * adapter and its search remain for when ECAP is switched on again). The tests run against a stand-in ECAP (see
 * {@link TestEcapConfiguration}).
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
    void aCourseIsTypedAndKeptExactlyAsEntered() throws Exception {
        TestEcapConfiguration.CALLS.set(0);
        save(List.of(course("CS301", "A name somebody typed"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].courseCode").value("CS301"))
                .andExpect(jsonPath("$.records[0].courseName").value("A name somebody typed"));
        assertEquals(0, TestEcapConfiguration.CALLS.get(), "ECAP is not asked anything when a course is saved");
    }

    @Test
    void aCodeEcapDoesNotKnowIsAcceptedAndEcapBeingDownDoesNotBlockManualEntry() throws Exception {
        TestEcapConfiguration.CALLS.set(0);
        save(List.of(course("NOPE101", "Invented but typed"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].courseCode").value("NOPE101"));

        TestEcapConfiguration.DOWN.set(true);
        save(List.of(course("NOPE101", "Invented but typed"), course("CS102", "Another course"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.records.length()").value(2));
        assertEquals(0, TestEcapConfiguration.CALLS.get());
    }

    @Test
    void theFormNamesTheTwoFieldsCourseAndCourseCodeAndRequiresBoth() throws Exception {
        Map<String, Object> noName = course("CS301", "");
        save(List.of(noName)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors['records[0].courseName']").value("Course is required."));
        Map<String, Object> noCode = course("", "Data Structures");
        save(List.of(noCode)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors['records[0].courseCode']").value("Course Code is required."));
    }

    @Test
    void semestersAreOneToEightForEveryDepartment() throws Exception {
        for (int semester : new int[] {1, 8}) {
            Map<String, Object> c = course("CS30" + semester, "Course " + semester);
            c.put("semester", semester);
            save(List.of(c)).andExpect(status().isOk());
        }
        for (int semester : new int[] {0, 9, 12}) {
            Map<String, Object> c = course("CS399", "Too late");
            c.put("semester", semester);
            save(List.of(c)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors['records[0].semester']").exists());
        }
        for (String dept : new String[] {"ECE", "ME"}) {
            Map<String, Object> c = course("X1", "Another department");
            c.put("branch", dept);
            c.put("semester", 8);
            save(List.of(c)).andExpect(status().isOk());
        }
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
