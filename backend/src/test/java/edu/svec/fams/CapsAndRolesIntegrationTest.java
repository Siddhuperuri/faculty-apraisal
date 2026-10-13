package edu.svec.fams;

import static edu.svec.fams.SectionSchemaTest.rec;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import edu.svec.fams.reference.ReferenceData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * Scoring policy data: limits on funded projects, outreach and memberships, the institutional roles that carry marks in
 * Teaching & Learning, and the Teaching & Learning parts an administrator can set for a cadre.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CapsAndRolesIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestDb db;
    @Autowired JdbcClient jdbc;
    @Autowired AppraisalService appraisals;
    @Autowired ReferenceData reference;

    FamsUserPrincipal faculty, admin;
    long id;

    @BeforeEach
    void setUp() {
        db.reset();
        faculty = db.faculty("f1@test.edu", "E001", "CSE", "PROFESSOR");
        admin = db.user("admin@test.edu", Role.ADMIN);
        id = appraisals.create(faculty);
    }

    // ---- helpers ----

    private ResultActions send(FamsUserPrincipal who, String method, String path, Object body) throws Exception {
        var req = switch (method) {
            case "POST" -> post(path);
            case "PUT" -> put(path);
            default -> get(path);
        };
        req = req.with(user(who)).with(csrf().asHeader());
        if (body != null) req = req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return mvc.perform(req);
    }

    private JsonNode sheet(long appraisal) throws Exception {
        return json.readTree(send(faculty, "GET", "/api/appraisals/" + appraisal, null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode score(JsonNode view, String criterion) {
        for (JsonNode s : view.get("scores")) {
            if (s.get("criterion").asText().equals(criterion)) return s;
        }
        throw new AssertionError(criterion);
    }

    private void outreach(int n) {
        for (int i = 0; i < n; i++) {
            jdbc.sql("INSERT INTO outreach (appraisal_id, role, event_activity, organization, event_date) VALUES (?, 'RESOURCE_PERSON', ?, 'X', '2026-01-10')")
                    .params(id, "Talk " + i).update();
        }
    }

    private Map<String, Object> marks(int b1, Map<String, Integer> others) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("TEACHING_LEARNING", b1);
        m.put("STUDENT_MENTORING", 15);
        m.put("FDP_CERTIFICATIONS", 15);
        m.put("ADMINISTRATIVE", 15);
        m.putAll(others);
        return m;
    }

    private long professorCadre() {
        return jdbc.sql("SELECT id FROM cadres WHERE code = 'PROFESSOR'").query(Long.class).single();
    }

    private long year() {
        return jdbc.sql("SELECT id FROM academic_years WHERE name = '2025-26'").query(Long.class).single();
    }

    // ---- limits ----

    @Test
    void outreachAndMembershipsStopAtTheLimitsOfThePolicy() throws Exception {
        outreach(5);                                                   // 5 x 5 = 25, limited to 10
        for (int i = 0; i < 3; i++) {
            jdbc.sql("INSERT INTO memberships_awards (appraisal_id, item, awarding_body, level, year) VALUES (?, ?, 'IEEE', 'INTL', 2025)")
                    .params(id, "Membership " + i).update();            // 3 x 5 = 15, limited to 5
        }
        JsonNode view = sheet(id);
        assertEquals(10, score(view, "OUTREACH").get("maxMarks").asInt());
        assertEquals(10, score(view, "OUTREACH").get("score").asInt());
        assertEquals(5, score(view, "MEMBERSHIPS_AWARDS").get("maxMarks").asInt());
        assertEquals(5, score(view, "MEMBERSHIPS_AWARDS").get("score").asInt());
        assertEquals(true, score(view, "FUNDED_PROJECTS").get("maxMarks").isNull());   // no limit on the criterion since version 5
    }

    @Test
    void theLimitComesFromThePolicySoZeroMeansNoneAndAHigherFigureRaisesIt() throws Exception {
        outreach(5);
        jdbc.sql("UPDATE appraisal_scores SET max_marks = 0 WHERE appraisal_id = ? AND criterion = 'OUTREACH'").param(id).update();
        JsonNode none = score(sheet(id), "OUTREACH");
        assertEquals(25, none.get("score").asInt());
        assertEquals(true, none.get("maxMarks").isNull());

        jdbc.sql("UPDATE appraisal_scores SET max_marks = 20 WHERE appraisal_id = ? AND criterion = 'OUTREACH'").param(id).update();
        assertEquals(20, score(sheet(id), "OUTREACH").get("score").asInt());
    }

    @Test
    void anAdministratorPublishesLimitsWithTheMarksAndNewAppraisalsUseThem() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("academicYearId", year());
        body.put("cadreId", professorCadre());
        body.put("marks", marks(30, Map.of("FUNDED_PROJECTS", 7, "OUTREACH", 12, "MEMBERSHIPS_AWARDS", 0)));
        send(admin, "POST", "/api/admin/policies", body).andExpect(status().isCreated())
                .andExpect(jsonPath("$.marks.FUNDED_PROJECTS").value(7))
                .andExpect(jsonPath("$.marks.OUTREACH").value(12))
                .andExpect(jsonPath("$.total").value(75));                 // B1 to B4 only: the limits are not part of the total

        FamsUserPrincipal other = db.faculty("f2@test.edu", "E002", "CSE", "PROFESSOR");
        long second = appraisals.create(other);
        JsonNode view = json.readTree(send(other, "GET", "/api/appraisals/" + second, null).andReturn().getResponse().getContentAsString());
        assertEquals(7, score(view, "FUNDED_PROJECTS").get("maxMarks").asInt());
        assertEquals(12, score(view, "OUTREACH").get("maxMarks").asInt());
        assertEquals(true, score(view, "MEMBERSHIPS_AWARDS").get("maxMarks").isNull());
    }

    // ---- the parts of Teaching & Learning ----

    @Test
    void theTeachingPartsOfACadreAreSetWithThePolicyAndMustAddUpToTheMaximum() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("academicYearId", year());
        body.put("cadreId", professorCadre());
        body.put("marks", marks(30, Map.of()));
        body.put("components", Map.of("TEACHING_LEARNING", List.of(
                Map.of("description", "Workload & course delivery", "maxMarks", 12),
                Map.of("description", "course-file/assessment quality", "maxMarks", 10),
                Map.of("description", "innovative/remedial/advanced-learning practices", "maxMarks", 8))));
        send(admin, "POST", "/api/admin/policies", body).andExpect(status().isCreated())
                .andExpect(jsonPath("$.components.TEACHING_LEARNING[0].maxMarks").value(12))
                .andExpect(jsonPath("$.components.TEACHING_LEARNING[2].maxMarks").value(8));

        body.put("components", Map.of("TEACHING_LEARNING", List.of(
                Map.of("description", "Workload & course delivery", "maxMarks", 12),
                Map.of("description", "course-file/assessment quality", "maxMarks", 10))));
        send(admin, "POST", "/api/admin/policies", body).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors['components.TEACHING_LEARNING']").value("The parts add up to 22 but the maximum is 30."));

        // the new weightage shows on a new appraisal, with the calculation spelled out
        FamsUserPrincipal other = db.faculty("f2@test.edu", "E002", "CSE", "PROFESSOR");
        long second = appraisals.create(other);
        jdbc.sql("INSERT INTO teaching_courses (appraisal_id, course_code, course_name, course_type, program, branch, semester, hours_per_week, pass_percentage)"
                + " VALUES (?, 'C1', 'Intro', 'THEORY', 'B_TECH', 'CSE', 3, 4, 90)").param(second).update();
        JsonNode view = json.readTree(send(other, "GET", "/api/appraisals/" + second, null).andReturn().getResponse().getContentAsString());
        JsonNode teaching = score(view, "TEACHING_LEARNING");
        assertEquals(12, teaching.get("components").get(0).get("maxMarks").asInt());
        // a Professor teaches 2 courses a year, so each earns 12 / 2 = 6 of the workload part's 12
        String line = teaching.get("breakdown").get(0).get("description").asText();
        assertEquals("Workload & course delivery (Max marks: 12)", line);
        assertEquals(6, teaching.get("components").get(0).get("awarded").asInt());
    }

    // ---- institutional roles ----

    @Test
    void roleMarksAreAddedToTeachingAndLearningWithinItsMaximum() throws Exception {
        send(faculty, "PUT", "/api/appraisals/" + id + "/sections/institutional-roles", Map.of("records", List.of(
                rec("role", "HOD", "remarks", "CSE"), rec("role", "ASSOCIATE_DEAN"))))
                .andExpect(status().isOk());
        JsonNode teaching = score(sheet(id), "TEACHING_LEARNING");
        assertEquals(8, teaching.get("score").asInt());                 // HoD 5 + Associate Dean 3, no courses
        List<String> lines = new ArrayList<>();
        teaching.get("breakdown").forEach(l -> lines.add(l.get("description").asText() + "=" + l.get("marks").asInt()));
        assertEquals(true, lines.contains("Institutional role: Head of the Department (HoD)=5"), lines.toString());
        assertEquals(true, lines.contains("Institutional role: Associate Dean=3"), lines.toString());

        // the same role twice is refused
        send(faculty, "PUT", "/api/appraisals/" + id + "/sections/institutional-roles", Map.of("records", List.of(
                rec("role", "HOD"), rec("role", "HOD")))).andExpect(status().isBadRequest());
    }

    @Test
    void theTotalNeverPassesTheCriterionsMaximum() throws Exception {
        for (int i = 1; i <= 8; i++) {
            jdbc.sql("INSERT INTO teaching_courses (appraisal_id, course_code, course_name, course_type, program, branch, semester, hours_per_week, pass_percentage)"
                    + " VALUES (?, ?, 'Intro', 'THEORY', 'B_TECH', 'CSE', 3, 4, 90)").params(id, "C" + i).update();
        }
        send(faculty, "PUT", "/api/appraisals/" + id + "/sections/institutional-roles", Map.of("records", List.of(rec("role", "HOD")))).andExpect(status().isOk());
        JsonNode teaching = score(sheet(id), "TEACHING_LEARNING");
        assertEquals(35, teaching.get("maxMarks").asInt());
        assertEquals(35, teaching.get("score").asInt());                // 35 for the courses, +5 for the role, limited to 35
    }

    @Test
    void aRoleAnAdministratorAddsEarnsNothingUntilItsMarksAreSet() throws Exception {
        send(admin, "POST", "/api/admin/lists/INSTITUTIONAL_ROLE/values", Map.of("code", "COORDINATOR_IQAC", "label", "IQAC Coordinator"))
                .andExpect(status().isCreated());
        send(faculty, "PUT", "/api/appraisals/" + id + "/sections/institutional-roles", Map.of("records", List.of(rec("role", "COORDINATOR_IQAC"))))
                .andExpect(status().isOk());
        assertEquals(0, score(sheet(id), "TEACHING_LEARNING").get("score").asInt());

        send(admin, "GET", "/api/admin/role-marks?academicYearId=" + year(), null).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code=='HOD')].marks").value(5))
                .andExpect(jsonPath("$[?(@.code=='COORDINATOR_IQAC')].marks").value(0));
        send(admin, "PUT", "/api/admin/role-marks", Map.of("academicYearId", year(), "marks", Map.of("COORDINATOR_IQAC", 2, "HOD", 6)))
                .andExpect(status().isOk());
        assertEquals(2, score(sheet(id), "TEACHING_LEARNING").get("score").asInt());
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM audit_logs WHERE action = 'ROLE_MARKS_SET'").query(Integer.class).single());

        send(admin, "PUT", "/api/admin/role-marks", Map.of("academicYearId", year(), "marks", Map.of("NOPE", 2))).andExpect(status().isBadRequest());
        send(admin, "PUT", "/api/admin/role-marks", Map.of("academicYearId", year(), "marks", Map.of("HOD", -1))).andExpect(status().isBadRequest());
        send(faculty, "PUT", "/api/admin/role-marks", Map.of("academicYearId", year(), "marks", Map.of("HOD", 1))).andExpect(status().isForbidden());
    }

    @Test
    void aRoleSwitchedOffIsNotOfferedButKeepsItsMarksOnRecordsThatHoldIt() throws Exception {
        send(faculty, "PUT", "/api/appraisals/" + id + "/sections/institutional-roles", Map.of("records", List.of(rec("role", "ASSOCIATE_DEAN"))))
                .andExpect(status().isOk());
        long dean = jdbc.sql("SELECT id FROM reference_values WHERE list_code = 'INSTITUTIONAL_ROLE' AND code = 'DEAN'").query(Long.class).single();
        // Dean is locked, as the roles the policy carries marks for are: the wording can change, it cannot be switched off
        send(admin, "PUT", "/api/admin/lists/values/" + dean, Map.of("active", false)).andExpect(status().isBadRequest());
        reference.refresh();
        assertEquals(3, score(sheet(id), "TEACHING_LEARNING").get("score").asInt());
    }
}
