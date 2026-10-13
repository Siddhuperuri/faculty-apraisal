package edu.svec.fams;

import static edu.svec.fams.SectionSchemaTest.rec;
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
import edu.svec.fams.reference.ReferenceData;
import edu.svec.fams.report.ReportLabelsProbe;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
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
 * Dropdown and reference data live in the database and are managed by an administrator: the lists are seeded, the forms
 * and the validation read them, a value that is added is accepted at once, a value that is switched off stops being
 * offered but stays on the records that hold it, and values the application's own rules depend on cannot be switched off.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReferenceDataTest {

    @Autowired MockMvc mvc;
    @Autowired TestDb db;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @Autowired AppraisalService appraisals;
    @Autowired ReferenceData reference;

    FamsUserPrincipal admin, faculty;
    long id;
    /** The seeded sequence of every value, put back after a test that moved some. */
    Map<Long, Integer> seededOrder;

    @BeforeEach
    void setUp() {
        db.reset();
        seededOrder = new java.util.LinkedHashMap<>();
        jdbc.sql("SELECT id, sort_order FROM reference_values").query((rs, n) -> seededOrder.put(rs.getLong(1), rs.getInt(2))).list();
        admin = db.user("admin@test.edu", Role.ADMIN);
        faculty = db.faculty("f1@test.edu", "E001", "CSE", "ASST_PROF");
        id = appraisals.create(faculty);
    }

    @AfterEach
    void putTheListsBack() {
        db.reset();
        jdbc.sql("UPDATE reference_values SET label = 'Course Coordinator', report_label = NULL WHERE list_code = 'COURSE_ROLE' AND code = 'COORDINATOR'").update();
        jdbc.sql("UPDATE reference_values SET sort_order = -id").update();   // the numbers are unique: go through free ones
        seededOrder.forEach((valueId, order) ->
                jdbc.sql("UPDATE reference_values SET sort_order = ? WHERE id = ?").params(order, valueId).update());
        reference.refresh();
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

    private JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private long valueId(String list, String code) {
        return jdbc.sql("SELECT id FROM reference_values WHERE list_code = ? AND code = ? LIMIT 1").params(list, code).query(Long.class).single();
    }

    private Map<String, Object> course(String section, String branch) {
        return rec("courseCode", "CS101", "courseRole", "INSTRUCTOR", "courseName", "Intro", "courseType", "THEORY",
                "program", "B_TECH", "branch", branch, "semester", 3, "section", section, "hoursPerWeek", 4, "passPercentage", 90);
    }

    private ResultActions saveCourses(Object... courses) throws Exception {
        return send(faculty, "PUT", "/api/appraisals/" + id + "/sections/teaching-courses", Map.of("records", List.of(courses)));
    }

    // ---- what is seeded ----

    @Test
    void everyDropdownListIsSeededAndTheLockedValuesAreTheOnesTheRulesUse() throws Exception {
        JsonNode lists = body(send(admin, "GET", "/api/admin/lists", null).andExpect(status().isOk()));
        assertTrue(lists.size() >= 30);
        for (JsonNode l : lists) {
            assertFalse(l.get("values").isEmpty(), l.get("code").asText() + " has no values");
            for (JsonNode v : l.get("values")) {
                assertFalse(v.get("label").asText().isBlank(), v.get("code").asText());
                // everything is on, except the semesters beyond 8 and the old closed "Others" of the outreach roles, which migrations switched off
                boolean beyondEight = l.get("code").asText().equals("SEMESTER") && Integer.parseInt(v.get("code").asText()) > 8;
                boolean closedOthers = l.get("code").asText().equals("OUTREACH_ROLE") && v.get("code").asText().equals("OTHERS");   // V38 replaced it by "Other"
                assertEquals(!beyondEight && !closedOthers, v.get("active").asBoolean(), l.get("code").asText() + " " + v.get("code").asText());
            }
        }
        assertEquals(List.of("B_TECH", "M_TECH", "DIPLOMA", "MBA", "PHARMACY"), List.copyOf(reference.active("PROGRAM")));
        assertEquals(List.of("CSE", "AIML", "CAI", "AIM", "CSDS", "ECE", "EEE", "ME", "CE", "BSH"), reference.activeUnder("BRANCH", "B_TECH"));
        assertEquals(List.of("A", "B", "C", "D", "E"), reference.active("SECTION"));
        assertEquals(List.of("1", "2", "3", "4", "5", "6"), reference.active("HOURS_PER_WEEK"));
        // the values the scoring rules and screens depend on cannot be switched off
        for (String[] locked : new String[][] {{"JOURNAL_INDEXING", "SCI_SCIE"}, {"JOURNAL_INDEXING", "SCOPUS"}, {"FUNDED_STATUS", "SANCTIONED"},
                {"PATENT_STATUS", "GRANTED"}, {"BOOK_TYPE", "CHAPTER"}, {"PROJECT_LEVEL", "UG"}, {"PHD_STATUS", "PURSUING"}, {"CERT_PLATFORM", "OTHER"}}) {
            send(admin, "PUT", "/api/admin/lists/values/" + valueId(locked[0], locked[1]), Map.of("active", false))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors.active").exists());
        }
    }

    @Test
    void theFormsMetaComesFromTheListsWithLabelsAndSuggestions() throws Exception {
        JsonNode meta = body(send(faculty, "GET", "/api/sections/meta", null).andExpect(status().isOk()));
        JsonNode courses = null;
        JsonNode roles = null;
        JsonNode bodies = null;
        for (JsonNode s : meta) {
            if (s.get("key").asText().equals("teaching-courses")) courses = s;
            if (s.get("key").asText().equals("administrative-roles")) roles = s;
            if (s.get("key").asText().equals("memberships-awards")) bodies = s;
        }
        JsonNode program = field(courses, "program");
        assertEquals("B.Tech", program.get("labels").get("B_TECH").asText());
        assertEquals(5, program.get("allowed").size());
        JsonNode branch = field(courses, "branch");
        assertEquals("program", branch.get("dependsOn").asText());
        assertEquals(5, branch.get("allowedBy").get("M_TECH").size());
        assertEquals("AI & ML", branch.get("labels").get("AIML").asText());
        assertEquals(1, field(courses, "hoursPerWeek").get("min").asInt());
        assertEquals(6, field(courses, "hoursPerWeek").get("max").asInt());
        JsonNode roleCode = field(roles, "roleCode");
        assertEquals("scope", roleCode.get("dependsOn").asText());
        assertTrue(roleCode.get("allowedBy").get("INSTITUTE").toString().contains("IQAC"));
        assertTrue(roleCode.get("allowedBy").get("DEPARTMENT").toString().contains("CLASS_COORDINATOR"));
        assertEquals("OTHER", roleCode.get("allowedBy").get("INSTITUTE").get(roleCode.get("allowedBy").get("INSTITUTE").size() - 1).asText());
        assertTrue(field(bodies, "awardingBody").get("suggestions").get("").toString().contains("IEEE"));
    }

    private static JsonNode field(JsonNode section, String name) {
        for (JsonNode f : section.get("fields")) {
            if (f.get("name").asText().equals(name)) return f;
        }
        throw new AssertionError("no field " + name);
    }

    // ---- adding and switching off ----

    @Test
    void aValueAnAdministratorAddsIsAcceptedAtOnceAndNoOtherIsStillRefused() throws Exception {
        saveCourses(course("F", "CSE")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors['records[0].section']").value("Section must be one of: A, B, C, D, E."));

        send(admin, "POST", "/api/admin/lists/SECTION/values", Map.of("code", "f", "label", "F")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("F")).andExpect(jsonPath("$.active").value(true));
        saveCourses(course("F", "CSE")).andExpect(status().isOk()).andExpect(jsonPath("$.records[0].section").value("F"));
        saveCourses(course("G", "CSE")).andExpect(status().isBadRequest());

        // the meta now offers it, after the others
        JsonNode meta = body(send(faculty, "GET", "/api/sections/meta", null));
        for (JsonNode s : meta) {
            if (s.get("key").asText().equals("teaching-courses")) {
                assertEquals("[\"A\",\"B\",\"C\",\"D\",\"E\",\"F\"]", field(s, "section").get("allowed").toString());
            }
        }
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM audit_logs WHERE action = 'REFERENCE_VALUE_ADDED'").query(Integer.class).single());
    }

    @Test
    void aNumberListSetsItsOwnBoundsAndAcceptsOnlyWholeNumbers() throws Exception {
        saveCourses(rec("courseCode", "CS101", "courseRole", "INSTRUCTOR", "courseName", "Intro", "courseType", "THEORY", "program", "B_TECH",
                "branch", "CSE", "semester", 3, "section", "A", "hoursPerWeek", 7, "passPercentage", 90)).andExpect(status().isBadRequest());
        send(admin, "POST", "/api/admin/lists/HOURS_PER_WEEK/values", Map.of("code", "7", "label", "7")).andExpect(status().isCreated());
        saveCourses(rec("courseCode", "CS101", "courseRole", "INSTRUCTOR", "courseName", "Intro", "courseType", "THEORY", "program", "B_TECH",
                "branch", "CSE", "semester", 3, "section", "A", "hoursPerWeek", 7, "passPercentage", 90)).andExpect(status().isOk());
        send(admin, "POST", "/api/admin/lists/HOURS_PER_WEEK/values", Map.of("code", "four", "label", "four"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.code").exists());
        send(admin, "POST", "/api/admin/lists/SEMESTER/values", Map.of("code", "13", "label", "13")).andExpect(status().isCreated());
    }

    @Test
    void aBranchBelongsToAProgramAndIsOnlyAcceptedUnderIt() throws Exception {
        send(admin, "POST", "/api/admin/lists/BRANCH/values", Map.of("code", "DS", "label", "Data Science")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.parent").exists());                 // a branch needs its program
        send(admin, "POST", "/api/admin/lists/BRANCH/values", Map.of("code", "DS", "label", "Data Science", "parent", "NOPE"))
                .andExpect(status().isBadRequest());
        send(admin, "POST", "/api/admin/lists/BRANCH/values", Map.of("code", "DS", "label", "Data Science", "parent", "M_TECH"))
                .andExpect(status().isCreated());
        send(admin, "POST", "/api/admin/lists/BRANCH/values", Map.of("code", "DS", "label", "Data Science", "parent", "M_TECH"))
                .andExpect(status().isBadRequest());                                  // already there

        Map<String, Object> mtech = course("A", "DS");
        mtech.put("program", "M_TECH");
        saveCourses(mtech).andExpect(status().isOk());
        saveCourses(course("A", "DS")).andExpect(status().isBadRequest())             // DS is not a B.Tech branch
                .andExpect(jsonPath("$.fieldErrors['records[0].branch']").exists());
    }

    @Test
    void aValueSwitchedOffIsNotOfferedButStaysOnTheRecordsThatHoldIt() throws Exception {
        Map<String, Object> theory = course("B", "CSE");
        saveCourses(theory).andExpect(status().isOk());
        long recordId = body(send(faculty, "GET", "/api/appraisals/" + id + "/sections/teaching-courses", null)).get("records").get(0).get("id").asLong();

        send(admin, "PUT", "/api/admin/lists/values/" + valueId("SECTION", "B"), Map.of("active", false)).andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        // not offered to a new course ...
        saveCourses(course("B", "CSE")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors['records[0].section']").value("Section must be one of: A, C, D, E."));
        // ... but the saved course keeps it, can be saved again unchanged, and another field of it can be edited
        Map<String, Object> same = course("B", "CSE");
        same.put("id", recordId);
        same.put("courseName", "Intro, renamed");
        saveCourses(same).andExpect(status().isOk()).andExpect(jsonPath("$.records[0].section").value("B"));
        // changing it to a switched-off value that it did not hold is refused
        Map<String, Object> other = course("A", "CSE");
        other.put("id", recordId);
        saveCourses(other).andExpect(status().isOk());
        Map<String, Object> back = course("B", "CSE");
        back.put("id", recordId);
        saveCourses(back).andExpect(status().isBadRequest());

        // its label is still known, so old records read properly
        JsonNode meta = body(send(faculty, "GET", "/api/sections/meta", null));
        for (JsonNode s : meta) {
            if (s.get("key").asText().equals("teaching-courses")) {
                assertEquals("B", field(s, "section").get("labels").get("B").asText());
                assertFalse(field(s, "section").get("allowed").toString().contains("\"B\""));
            }
        }
    }

    @Test
    void renamingAValueChangesItsLabelOnTheFormAndTheReportButNeverItsCode() throws Exception {
        long coordinator = valueId("COURSE_ROLE", "COORDINATOR");
        send(admin, "PUT", "/api/admin/lists/values/" + coordinator,
                Map.of("label", "Course Co-ordinator", "reportLabel", "Co-ordinator", "code", "SOMETHING_ELSE")).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("COORDINATOR"))
                .andExpect(jsonPath("$.label").value("Course Co-ordinator"));
        JsonNode meta = body(send(faculty, "GET", "/api/sections/meta", null));
        for (JsonNode s : meta) {
            if (s.get("key").asText().equals("teaching-courses")) {
                assertEquals("Course Co-ordinator", field(s, "courseRole").get("labels").get("COORDINATOR").asText());
            }
        }
        ReportLabelsProbe.use(reference::reportLabel);
        assertEquals("Co-ordinator", ReportLabelsProbe.label("COORDINATOR"));
        send(admin, "PUT", "/api/admin/lists/values/" + coordinator, Map.of("label", " ")).andExpect(status().isBadRequest());
        send(admin, "PUT", "/api/admin/lists/values/" + coordinator, Map.of("sortOrder", -5)).andExpect(status().isBadRequest());
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM audit_logs WHERE action = 'REFERENCE_VALUE_UPDATED'").query(Integer.class).single());
    }

    // ---- the sequence, moved one place at a time ----

    private ResultActions move(FamsUserPrincipal who, long valueId, String direction) throws Exception {
        return send(who, "POST", "/api/admin/lists/values/" + valueId + "/move", Map.of("direction", direction));
    }

    private List<String> sequence(String list) {
        return jdbc.sql("SELECT code FROM reference_values WHERE list_code = ? AND parent_code = '' ORDER BY sort_order").param(list)
                .query(String.class).list();
    }

    private List<String> offeredOnTheForm(String field) throws Exception {
        for (JsonNode s : body(send(faculty, "GET", "/api/sections/meta", null))) {
            if (s.get("key").asText().equals("teaching-courses")) {
                List<String> out = new java.util.ArrayList<>();
                for (JsonNode a : field(s, field).get("allowed")) out.add(a.asText());
                return out;
            }
        }
        throw new AssertionError("no teaching-courses section");
    }

    @Test
    void theOrderOfAListIsTheOrderItsValuesAreOffered() throws Exception {
        assertEquals(List.of("THEORY", "LAB"), reference.active("COURSE_TYPE"));
        move(admin, valueId("COURSE_TYPE", "LAB"), "UP").andExpect(status().isOk()).andExpect(jsonPath("$.code").value("LAB"));
        assertEquals(List.of("LAB", "THEORY"), reference.active("COURSE_TYPE"));
        assertEquals(List.of("LAB", "THEORY"), List.copyOf(reference.labels("COURSE_TYPE").keySet()));
        assertEquals(List.of("LAB", "THEORY"), offeredOnTheForm("courseType"));
    }

    @Test
    void movingTheFirstMiddleAndLastValuesOfAListSavesAtOnceAndIsOfferedEverywhere() throws Exception {
        List<String> start = sequence("SECTION");
        assertEquals(List.of("A", "B", "C", "D", "E"), start);

        move(admin, valueId("SECTION", "C"), "UP").andExpect(status().isOk());                    // a middle value
        assertEquals(List.of("A", "C", "B", "D", "E"), sequence("SECTION"));
        move(admin, valueId("SECTION", "E"), "UP").andExpect(status().isOk());                    // the last value
        assertEquals(List.of("A", "C", "B", "E", "D"), sequence("SECTION"));
        move(admin, valueId("SECTION", "A"), "DOWN").andExpect(status().isOk());                  // the first value
        assertEquals(List.of("C", "A", "B", "E", "D"), sequence("SECTION"));

        // past either end changes nothing and is not an error
        move(admin, valueId("SECTION", "C"), "UP").andExpect(status().isOk());
        move(admin, valueId("SECTION", "D"), "DOWN").andExpect(status().isOk());
        assertEquals(List.of("C", "A", "B", "E", "D"), sequence("SECTION"));

        // persisted: re-read from the database, the forms, the administration page and the label map all agree
        reference.refresh();
        assertEquals(List.of("C", "A", "B", "E", "D"), reference.active("SECTION"));
        assertEquals(List.of("C", "A", "B", "E", "D"), offeredOnTheForm("section"));
        JsonNode lists = body(send(admin, "GET", "/api/admin/lists", null));
        for (JsonNode l : lists) {
            if (!l.get("code").asText().equals("SECTION")) continue;
            List<String> shown = new java.util.ArrayList<>();
            java.util.Set<Integer> numbers = new java.util.HashSet<>();
            for (JsonNode v : l.get("values")) {
                shown.add(v.get("code").asText());
                assertTrue(numbers.add(v.get("sortOrder").asInt()), "the sequence is unique");
            }
            assertEquals(List.of("C", "A", "B", "E", "D"), shown);
        }
        // everything but the place of each value is untouched
        assertEquals("C", jdbc.sql("SELECT label FROM reference_values WHERE list_code = 'SECTION' AND code = 'C'").query(String.class).single());
        assertEquals(5, reference.active("SECTION").size());
        // each real move is audited; a move that changed nothing is not
        assertEquals(3, jdbc.sql("SELECT COUNT(*) FROM audit_logs WHERE action = 'REFERENCE_VALUE_MOVED'").query(Integer.class).single());
    }

    @Test
    void theSequenceOfEveryListIsAlwaysUniqueAndAFreshValueGoesLast() throws Exception {
        move(admin, valueId("SECTION", "B"), "DOWN").andExpect(status().isOk());
        send(admin, "POST", "/api/admin/lists/SECTION/values", Map.of("code", "F", "label", "F")).andExpect(status().isCreated());
        assertEquals(List.of("A", "C", "B", "D", "E", "F"), sequence("SECTION"));
        assertEquals(0, jdbc.sql("""
                SELECT COUNT(*) FROM (SELECT list_code, sort_order FROM reference_values GROUP BY list_code, sort_order HAVING COUNT(*) > 1) d""")
                .query(Integer.class).single());
        // the database refuses a duplicate place outright
        try {
            jdbc.sql("UPDATE reference_values SET sort_order = (SELECT s FROM (SELECT sort_order s FROM reference_values "
                    + "WHERE list_code = 'SECTION' AND code = 'A') x) WHERE list_code = 'SECTION' AND code = 'B'").update();
            throw new AssertionError("a duplicate sequence number was accepted");
        } catch (org.springframework.dao.DataIntegrityViolationException expected) {
            // good
        }
    }

    @Test
    void aSwitchedOffValueKeepsItsPlaceIsSteppedOverAndCannotBeMoved() throws Exception {
        send(admin, "PUT", "/api/admin/lists/values/" + valueId("SECTION", "B"), Map.of("active", false)).andExpect(status().isOk());
        // C moves up past the switched-off B (which keeps its place) and swaps with A
        move(admin, valueId("SECTION", "C"), "UP").andExpect(status().isOk());
        assertEquals(List.of("C", "B", "A", "D", "E"), sequence("SECTION"));
        assertEquals(List.of("C", "A", "D", "E"), reference.active("SECTION"));
        move(admin, valueId("SECTION", "B"), "UP").andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.direction").exists());
    }

    @Test
    void aValueOfADependentListMovesOnlyAmongItsOwnParentsChoices() throws Exception {
        List<String> mTechBefore = reference.activeUnder("BRANCH", "M_TECH");
        assertEquals(List.of("CSE", "AIML", "CAI", "AIM", "CSDS", "ECE", "EEE", "ME", "CE", "BSH"), reference.activeUnder("BRANCH", "B_TECH"));
        long ece = jdbc.sql("SELECT id FROM reference_values WHERE list_code = 'BRANCH' AND code = 'ECE' AND parent_code = 'B_TECH'")
                .query(Long.class).single();
        move(admin, ece, "UP").andExpect(status().isOk());
        assertEquals(List.of("CSE", "AIML", "CAI", "AIM", "ECE", "CSDS", "EEE", "ME", "CE", "BSH"), reference.activeUnder("BRANCH", "B_TECH"));
        assertEquals(mTechBefore, reference.activeUnder("BRANCH", "M_TECH"), "another program's branches are not disturbed");
        move(admin, valueId("BRANCH", "CSE"), "UP");                       // the first CSE found: whichever parent, moving up the first one is a no-op
        assertEquals(0, jdbc.sql("""
                SELECT COUNT(*) FROM (SELECT list_code, sort_order FROM reference_values GROUP BY list_code, sort_order HAVING COUNT(*) > 1) d""")
                .query(Integer.class).single());
    }

    @Test
    void theMoveIsAdministratorsOnlyAndChecksItsInput() throws Exception {
        long c = valueId("SECTION", "C");
        move(faculty, c, "UP").andExpect(status().isForbidden());
        move(db.hod("hod@test.edu", "CSE"), c, "UP").andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/lists/values/" + c + "/move").with(csrf().asHeader()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"direction\":\"UP\"}")).andExpect(status().isUnauthorized());
        assertEquals(List.of("A", "B", "C", "D", "E"), sequence("SECTION"));        // nothing moved

        move(admin, c, "SIDEWAYS").andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.direction").exists());
        send(admin, "POST", "/api/admin/lists/values/" + c + "/move", Map.of()).andExpect(status().isBadRequest());
        move(admin, 999999, "UP").andExpect(status().isNotFound());
        // the old free-typed number is no longer accepted
        send(admin, "PUT", "/api/admin/lists/values/" + c, Map.of("sortOrder", 5)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.sortOrder").exists());
        assertEquals(List.of("A", "B", "C", "D", "E"), sequence("SECTION"));
    }

    @Test
    void aSuggestionListStillWorksAndAnAdministratorCanAddARoleThatGoesBeforeOther() throws Exception {
        send(admin, "POST", "/api/admin/lists/INSTITUTE_ROLE_SUGGESTION/values", Map.of("label", "Hostel Warden")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("Hostel Warden"));
        send(admin, "PUT", "/api/admin/lists/values/" + valueId("INSTITUTE_ROLE_SUGGESTION", "Hostel Warden"), Map.of("label", "Warden"))
                .andExpect(status().isBadRequest());

        // a new institute-level role is offered before "Other", which always stays last
        send(admin, "POST", "/api/admin/lists/ADMIN_ROLE/values", Map.of("code", "WARDEN", "label", "Hostel Warden", "parent", "INSTITUTE"))
                .andExpect(status().isCreated());
        List<String> offered = reference.activeUnder("ADMIN_ROLE", "INSTITUTE");
        assertEquals("WARDEN", offered.get(offered.size() - 2));
        assertEquals("OTHER", offered.get(offered.size() - 1));
        long other = jdbc.sql("SELECT id FROM reference_values WHERE list_code = 'ADMIN_ROLE' AND code = 'OTHER' AND parent_code = 'INSTITUTE'")
                .query(Long.class).single();
        send(admin, "POST", "/api/admin/lists/values/" + other + "/move", Map.of("direction", "UP")).andExpect(status().isBadRequest());
        long warden = valueId("ADMIN_ROLE", "WARDEN");
        send(admin, "POST", "/api/admin/lists/values/" + warden + "/move", Map.of("direction", "DOWN")).andExpect(status().isOk());
        offered = reference.activeUnder("ADMIN_ROLE", "INSTITUTE");
        assertEquals("OTHER", offered.get(offered.size() - 1));

        // the standard role is accepted, and so is a typed one under Other
        send(faculty, "PUT", "/api/appraisals/" + id + "/sections/administrative-roles", Map.of("records", List.of(
                rec("scope", "INSTITUTE", "roleCode", "WARDEN", "fromDate", "2025-07-01", "toDate", "2025-12-31"),
                rec("scope", "INSTITUTE", "roleCode", "OTHER", "roleOther", "Something nobody listed", "fromDate", "2025-07-01", "toDate", "2025-12-31"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].role").value("Hostel Warden"))
                .andExpect(jsonPath("$.records[1].role").value("Something nobody listed"));
    }

    // ---- who may ----

    @Test
    void onlyAnAdministratorCanReadOrChangeTheLists() throws Exception {
        send(faculty, "GET", "/api/admin/lists", null).andExpect(status().isForbidden());
        send(faculty, "POST", "/api/admin/lists/SECTION/values", Map.of("code", "F", "label", "F")).andExpect(status().isForbidden());
        send(faculty, "PUT", "/api/admin/lists/values/" + valueId("SECTION", "A"), Map.of("active", false)).andExpect(status().isForbidden());
        send(admin, "POST", "/api/admin/lists/NO_SUCH_LIST/values", Map.of("code", "X", "label", "X")).andExpect(status().isNotFound());
        send(admin, "PUT", "/api/admin/lists/values/999999", Map.of("label", "x")).andExpect(status().isNotFound());
    }
}
