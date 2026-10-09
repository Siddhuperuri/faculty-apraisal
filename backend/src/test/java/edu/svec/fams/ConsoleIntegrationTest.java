package edu.svec.fams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.svec.fams.appraisal.AppraisalService;
import edu.svec.fams.auth.FamsUserPrincipal;
import edu.svec.fams.auth.Role;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** The Head of the Department's, Principal's and administrator's consoles: what they count, and what they must not reveal. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ConsoleIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestDb db;
    @Autowired JdbcClient jdbc;
    @Autowired AppraisalService appraisals;

    TestHttp http;
    FamsUserPrincipal hodCse, hodEce, principal, director, admin;
    Map<String, Long> appraisalIds = new HashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        db.reset();
        http = new TestHttp(mvc, json);
        hodCse = db.hod("hod.cse@test.edu", "CSE");
        hodEce = db.hod("hod.ece@test.edu", "ECE");
        principal = db.user("principal@test.edu", Role.PRINCIPAL);
        director = db.user("director@test.edu", Role.DIRECTOR);
        admin = db.user("admin@test.edu", Role.ADMIN);

        // CSE: two drafts, two people with the HoD, one forwarded to the Principal, one approved, one who has not started
        person("EMP-DRAFT", "CSE", "DRAFT");
        person("EMP-DRAFT2", "CSE", "DRAFT");
        person("EMP-SUB", "CSE", "SUBMITTED");
        person("EMP-REV", "CSE", "HOD_REVIEW");
        person("EMP-HAPP", "CSE", "HOD_APPROVED");
        person("EMP-APPR", "CSE", "APPROVED");
        person("EMP-NONE", "CSE", null);
        // a disabled account that never submitted anything is not part of the department's picture
        db.disable(db.faculty("gone@test.edu", "EMP-GONE", "CSE", "ASST_PROF"));
        // ECE: one with the HoD, one with the Principal
        person("EMP-ESUB", "ECE", "SUBMITTED");
        person("EMP-EPRIN", "ECE", "PRINCIPAL_REVIEW");
    }

    /** A faculty member (and, unless status is null, their appraisal in that status) in the department. */
    private void person(String employeeId, String dept, String status) {
        FamsUserPrincipal f = db.faculty(employeeId.toLowerCase() + "@test.edu", employeeId, dept, "ASST_PROF");
        if (status == null) return;
        long id = appraisals.create(f);
        appraisalIds.put(employeeId, id);
        if (status.equals("DRAFT")) return;
        jdbc.sql("UPDATE appraisals SET status = ?, submitted_at = CURRENT_TIMESTAMP, "
                + "final_approved_at = " + (status.equals("APPROVED") ? "CURRENT_TIMESTAMP" : "NULL") + " WHERE id = ?")
                .params(status, id).update();
    }

    private JsonNode get(String email, String path) throws Exception {
        return http.read(http.get(http.login(email), path).andExpect(status().isOk()));
    }

    private static Map<String, Integer> counts(JsonNode n) {
        Map<String, Integer> m = new LinkedHashMap<>();
        n.properties().forEach(e -> m.put(e.getKey(), e.getValue().asInt()));
        return m;
    }

    // ---- Head of the Department ----

    @Test
    void anHodSeesTheirDepartmentsFacultyGroupedByWhereTheyAre() throws Exception {
        JsonNode c = get("hod.cse@test.edu", "/api/hod/console");
        assertEquals("2025-26", c.get("year").get("name").asText());
        assertEquals(1, c.get("departments").size());
        JsonNode cse = c.get("departments").get(0);
        assertEquals("CSE", cse.get("code").asText());
        assertEquals(7, cse.get("faculty").asInt());                               // the disabled account is not counted
        Map<String, Integer> expected = Map.of("NOT_SUBMITTED", 3, "NEEDS_HOD", 2, "ONWARD", 1, "APPROVED", 1);
        assertEquals(expected, counts(cse.get("counts")));
        assertEquals(expected, counts(c.get("totals")));
        assertEquals(7, c.get("roster").size());
    }

    @Test
    void aDraftIsShownAsNotYetSubmittedWithNothingToOpen() throws Exception {
        JsonNode c = get("hod.cse@test.edu", "/api/hod/console");
        JsonNode draft = null, none = null, submitted = null;
        for (JsonNode r : c.get("roster")) {
            switch (r.get("employeeId").asText()) {
                case "EMP-DRAFT" -> draft = r;
                case "EMP-NONE" -> none = r;
                case "EMP-SUB" -> submitted = r;
                default -> { }
            }
        }
        for (JsonNode r : new JsonNode[] {draft, none}) {
            assertEquals("NOT_SUBMITTED", r.get("stage").asText());
            assertTrue(r.get("appraisalId").isNull());
            assertTrue(r.get("status").isNull());
            assertTrue(r.get("submittedAt").isNull() && r.get("updatedAt").isNull());
        }
        // the draft's appraisal id appears nowhere in the response, so its existence is not revealed
        assertFalse(c.toString().contains("\"appraisalId\":" + appraisalIds.get("EMP-DRAFT") + ","));
        // a submitted one can be opened, and the HoD really may open it
        long id = submitted.get("appraisalId").asLong();
        http.get(http.login("hod.cse@test.edu"), "/api/appraisals/" + id).andExpect(status().isOk());
    }

    @Test
    void theRosterPutsWhatNeedsTheHodFirstAndListsEveryoneOnce() throws Exception {
        JsonNode roster = get("hod.cse@test.edu", "/api/hod/console").get("roster");
        assertEquals("NEEDS_HOD", roster.get(0).get("stage").asText());
        assertEquals("NEEDS_HOD", roster.get(1).get("stage").asText());
        assertNotEquals("NEEDS_HOD", roster.get(2).get("stage").asText());
        List<String> ids = new ArrayList<>();
        roster.forEach(r -> ids.add(r.get("employeeId").asText()));
        assertEquals(ids.size(), ids.stream().distinct().count());
        assertFalse(ids.contains("EMP-GONE"));
    }

    @Test
    void anHodNeverSeesAnotherDepartment() throws Exception {
        JsonNode c = get("hod.ece@test.edu", "/api/hod/console");
        assertEquals(1, c.get("departments").size());
        assertEquals("ECE", c.get("departments").get(0).get("code").asText());
        assertEquals(2, c.get("departments").get(0).get("faculty").asInt());
        assertEquals(Map.of("NOT_SUBMITTED", 0, "NEEDS_HOD", 1, "ONWARD", 1, "APPROVED", 0),
                counts(c.get("departments").get(0).get("counts")));
        assertFalse(c.toString().contains("EMP-SUB") || c.toString().contains("EMP-NONE"));
        assertFalse(get("hod.cse@test.edu", "/api/hod/console").toString().contains("EMP-ESUB"));
    }

    @Test
    void anHodOfSeveralDepartmentsSeesEachAndTheTotal() throws Exception {
        jdbc.sql("INSERT INTO hod_assignments (user_id, department_id) VALUES (?, (SELECT id FROM departments WHERE code = 'ECE'))")
                .param(hodCse.id()).update();
        JsonNode c = get("hod.cse@test.edu", "/api/hod/console");
        assertEquals(2, c.get("departments").size());
        assertEquals(9, c.get("roster").size());
        assertEquals(3, c.get("totals").get("NEEDS_HOD").asInt());
        assertEquals(2, c.get("totals").get("ONWARD").asInt());
    }

    @Test
    void theHodConsoleCanBeShownForAnotherAcademicYear() throws Exception {
        jdbc.sql("INSERT INTO academic_years (name, start_date, end_date, active) VALUES ('2024-25', '2024-06-01', '2025-05-31', FALSE)").update();
        long old = jdbc.sql("SELECT id FROM academic_years WHERE name = '2024-25'").query(Long.class).single();
        JsonNode c = get("hod.cse@test.edu", "/api/hod/console?academicYearId=" + old);
        assertEquals("2024-25", c.get("year").get("name").asText());
        assertEquals(2, c.get("years").size());
        assertEquals(7, c.get("departments").get(0).get("counts").get("NOT_SUBMITTED").asInt());   // nobody has an appraisal in it
        http.get(http.login("hod.cse@test.edu"), "/api/hod/console?academicYearId=999999").andExpect(status().isNotFound());
    }

    @Test
    void whenNoYearIsOpenTheConsoleFallsBackToTheLatestYear() throws Exception {
        jdbc.sql("UPDATE academic_years SET active = FALSE").update();
        JsonNode c = get("hod.cse@test.edu", "/api/hod/console");
        assertEquals("2025-26", c.get("year").get("name").asText());
        assertEquals(7, c.get("roster").size());
    }

    // ---- Principal ----

    @Test
    void thePrincipalSeesTheWholeCollegeButOnlyCountsBeforeTheHodHasApproved() throws Exception {
        JsonNode c = get("principal@test.edu", "/api/principal/console");
        assertEquals(8, c.get("departments").size());
        Map<String, JsonNode> byCode = new HashMap<>();
        c.get("departments").forEach(d -> byCode.put(d.get("code").asText(), d));

        JsonNode cse = byCode.get("CSE");
        assertEquals(7, cse.get("faculty").asInt());
        assertEquals(1, cse.get("awaiting").asInt());
        assertEquals(1, cse.get("approved").asInt());
        assertEquals(5, cse.get("notYetWithYou").asInt());
        JsonNode ece = byCode.get("ECE");
        assertEquals(2, ece.get("faculty").asInt());
        assertEquals(1, ece.get("awaiting").asInt());
        assertEquals(1, ece.get("notYetWithYou").asInt());
        assertEquals(0, byCode.get("MBA").get("faculty").asInt());

        JsonNode t = c.get("totals");
        assertEquals(9, t.get("faculty").asInt());
        assertEquals(2, t.get("awaiting").asInt());
        assertEquals(1, t.get("approved").asInt());
        assertEquals(6, t.get("notYetWithYou").asInt());
        assertEquals(t.get("faculty").asInt(), t.get("awaiting").asInt() + t.get("approved").asInt() + t.get("notYetWithYou").asInt());
    }

    @Test
    void theDirectorTechnicalSeesExactlyTheConsoleThePrincipalSees() throws Exception {
        JsonNode asPrincipal = get("principal@test.edu", "/api/principal/console");
        JsonNode asDirector = get("director@test.edu", "/api/director/console");
        assertEquals(asPrincipal, asDirector);
        // and, like the Principal, is never told who is still with the HoD or in draft
        for (String hidden : new String[] {"EMP-DRAFT", "EMP-SUB", "EMP-REV", "EMP-NONE", "EMP-ESUB"}) {
            assertFalse(asDirector.toString().contains(hidden), hidden + " must not appear in the Director Technical's console");
        }
        // everything listed can really be opened by the Director
        MockHttpSession s = http.login("director@test.edu");
        for (JsonNode a : asDirector.get("awaitingList")) http.get(s, "/api/appraisals/" + a.get("appraisalId").asLong()).andExpect(status().isOk());
    }

    @Test
    void thePrincipalIsNeverToldWhoIsStillWithTheHodOrInDraft() throws Exception {
        JsonNode c = get("principal@test.edu", "/api/principal/console");
        String text = c.toString();
        for (String hidden : new String[] {"EMP-DRAFT", "EMP-DRAFT2", "EMP-SUB", "EMP-REV", "EMP-NONE", "EMP-ESUB", "EMP-GONE"}) {
            assertFalse(text.contains(hidden), hidden + " must not appear in the Principal's console");
        }
        List<String> waiting = new ArrayList<>();
        c.get("awaitingList").forEach(a -> waiting.add(a.get("employeeId").asText()));
        assertEquals(List.of("EMP-HAPP", "EMP-EPRIN").stream().sorted().toList(), waiting.stream().sorted().toList());
        // everything listed can really be opened by the Principal
        MockHttpSession s = http.login("principal@test.edu");
        for (JsonNode a : c.get("awaitingList")) http.get(s, "/api/appraisals/" + a.get("appraisalId").asLong()).andExpect(status().isOk());
    }

    @Test
    void theOldestWaitingAppraisalComesFirstAndTheListIsCapped() throws Exception {
        for (int i = 0; i < 12; i++) person("EMP-W" + i, "BSH", "HOD_APPROVED");
        for (int i = 0; i < 12; i++) {
            jdbc.sql("UPDATE appraisals SET updated_at = ? WHERE id = ?")
                    .params(java.sql.Timestamp.valueOf("2026-03-01 10:00:00").toLocalDateTime().plusDays(i), appraisalIds.get("EMP-W" + i)).update();
        }
        JsonNode c = get("principal@test.edu", "/api/principal/console");
        assertEquals(10, c.get("awaitingList").size());
        assertEquals(14, c.get("totals").get("awaiting").asInt());               // the real total is still reported
        // oldest first: the ten oldest are W0..W9 (the two fixtures were updated just now, so they come after)
        List<String> order = new ArrayList<>();
        c.get("awaitingList").forEach(a -> order.add(a.get("employeeId").asText()));
        assertEquals(List.of("EMP-W0", "EMP-W1", "EMP-W2", "EMP-W3", "EMP-W4", "EMP-W5", "EMP-W6", "EMP-W7", "EMP-W8", "EMP-W9"), order);
    }

    @Test
    void aClosedDepartmentThatStillHasFacultyStaysInThePrincipalsTotals() throws Exception {
        jdbc.sql("UPDATE departments SET active = FALSE WHERE code = 'ECE'").update();
        JsonNode c = get("principal@test.edu", "/api/principal/console");
        assertEquals(9, c.get("totals").get("faculty").asInt());
        boolean found = false;
        for (JsonNode d : c.get("departments")) found |= d.get("code").asText().equals("ECE");
        assertTrue(found);
    }

    // ---- administrator ----

    @Test
    void theAdministratorSeesCountsChecksAndActivityButNoNames() throws Exception {
        JsonNode o = get("admin@test.edu", "/api/admin/overview");
        Map<String, JsonNode> roles = new HashMap<>();
        o.get("accounts").forEach(r -> roles.put(r.get("role").asText(), r));
        assertEquals(9, roles.get("FACULTY").get("active").asInt());
        assertEquals(1, roles.get("FACULTY").get("disabled").asInt());
        assertEquals(2, roles.get("HOD").get("active").asInt());
        assertEquals(1, roles.get("PRINCIPAL").get("active").asInt());
        assertEquals(1, roles.get("ADMIN").get("active").asInt());

        JsonNode p = o.get("pipeline");
        assertEquals(1, p.get("NOT_STARTED").asInt());
        assertEquals(2, p.get("DRAFT").asInt());
        assertEquals(2, p.get("SUBMITTED").asInt());
        assertEquals(1, p.get("HOD_REVIEW").asInt());
        assertEquals(1, p.get("HOD_APPROVED").asInt());
        assertEquals(1, p.get("PRINCIPAL_REVIEW").asInt());
        assertEquals(1, p.get("APPROVED").asInt());

        String text = o.toString();
        assertFalse(text.contains("EMP-") || text.contains("Dr. "), "the overview must not name anyone");
    }

    @Test
    void neverSignedInCountsActiveAccountsThatHaveNotSignedInYet() throws Exception {
        JsonNode o = get("admin@test.edu", "/api/admin/overview");            // signing in records the administrator's login
        int activeUsers = jdbc.sql("SELECT COUNT(*) FROM users WHERE status = 'ACTIVE'").query(Integer.class).single();
        assertEquals(activeUsers - 1, o.get("neverSignedIn").asInt());
    }

    private Map<String, String> levels(JsonNode overview) {
        Map<String, String> m = new HashMap<>();
        overview.get("checks").forEach(c -> m.put(c.get("code").asText(), c.get("level").asText()));
        return m;
    }

    private String message(JsonNode overview, String code) {
        for (JsonNode c : overview.get("checks")) {
            if (c.get("code").asText().equals(code)) return c.get("message").asText();
        }
        return null;
    }

    @Test
    void theChecksPassOnAHealthySetupAndWarnAboutASingleAdministrator() throws Exception {
        Map<String, String> l = levels(get("admin@test.edu", "/api/admin/overview"));
        assertEquals("ok", l.get("OPEN_YEAR"));
        assertEquals("ok", l.get("POLICIES"));
        assertEquals("ok", l.get("HOD_COVERAGE"));
        assertEquals("ok", l.get("PRINCIPAL"));
        assertEquals("ok", l.get("FACULTY_RECORDS"));
        assertEquals("warn", l.get("ADMINS"));
        db.user("admin2@test.edu", Role.ADMIN);
        assertEquals("ok", levels(get("admin@test.edu", "/api/admin/overview")).get("ADMINS"));
    }

    @Test
    void theChecksSpotARealProblemTheMomentItExists() throws Exception {
        // no Head of the Department for a department that has faculty
        db.disable(hodEce);
        JsonNode o = get("admin@test.edu", "/api/admin/overview");
        assertEquals("problem", levels(o).get("HOD_COVERAGE"));
        assertTrue(message(o, "HOD_COVERAGE").contains("ECE"));
        assertFalse(message(o, "HOD_COVERAGE").contains("CSE"));

        // the Principal and the Director Technical stand at the same level: one of them is enough, and with neither it is a problem
        db.disable(principal);
        assertEquals("ok", levels(get("admin@test.edu", "/api/admin/overview")).get("PRINCIPAL"));
        db.disable(director);
        o = get("admin@test.edu", "/api/admin/overview");
        assertEquals("problem", levels(o).get("PRINCIPAL"));
        assertTrue(message(o, "PRINCIPAL").contains("Principal or Director Technical"));

        // a faculty account with no faculty record
        db.user("norecord@test.edu", Role.FACULTY);
        o = get("admin@test.edu", "/api/admin/overview");
        assertEquals("problem", levels(o).get("FACULTY_RECORDS"));
        assertTrue(message(o, "FACULTY_RECORDS").startsWith("1 active faculty account has no faculty record"));

        // a policy that does not total 100 (this cannot be published through the API, so put it in directly)
        long year = jdbc.sql("SELECT id FROM academic_years WHERE name = '2025-26'").query(Long.class).single();
        long cadre = jdbc.sql("SELECT id FROM cadres WHERE code = 'LECTURER'").query(Long.class).single();
        jdbc.sql("INSERT INTO scoring_policies (academic_year_id, cadre_id, version) VALUES (?,?,3)").params(year, cadre).update();
        long policy = jdbc.sql("SELECT id FROM scoring_policies WHERE academic_year_id = ? AND cadre_id = ? AND version = 3").params(year, cadre).query(Long.class).single();
        jdbc.sql("INSERT INTO scoring_policy_criteria (policy_id, criterion, max_marks) VALUES (?, 'TEACHING_LEARNING', 90)").param(policy).update();
        o = get("admin@test.edu", "/api/admin/overview");
        assertEquals("problem", levels(o).get("POLICIES"));
        assertTrue(message(o, "POLICIES").contains("Lecturer"));

        // no open year
        jdbc.sql("UPDATE academic_years SET active = FALSE").update();
        o = get("admin@test.edu", "/api/admin/overview");
        assertEquals("problem", levels(o).get("OPEN_YEAR"));
        assertNull(message(o, "POLICIES"));                                      // nothing to check without an open year
        jdbc.sql("UPDATE academic_years SET active = TRUE").update();
    }

    @Test
    void recentActivityIsTheLatestEightEntriesNewestFirst() throws Exception {
        MockHttpSession s = http.login("admin@test.edu");
        for (int i = 0; i < 10; i++) {
            http.post(s, "/api/admin/departments", Map.of("code", "Q" + i + "Z", "name", "Dept " + i)).andExpect(status().isCreated());
        }
        JsonNode o = http.read(http.get(s, "/api/admin/overview").andExpect(status().isOk()));
        assertEquals(8, o.get("recent").size());
        assertEquals("DEPARTMENT_CREATED", o.get("recent").get(0).get("action").asText());
        assertTrue(o.get("recent").get(0).get("id").asLong() > o.get("recent").get(7).get("id").asLong());
        assertNotNull(o.get("recent").get(0).get("actorEmail"));
    }

    // ---- who may open which console ----

    @Test
    void everyConsoleIsOpenOnlyToItsOwnRole() throws Exception {
        db.faculty("plain@test.edu", "EMP-PLAIN", "CSE", "ASST_PROF");
        Map<String, String[]> allowedOnly = Map.of(
                "/api/hod/console", new String[] {"hod.cse@test.edu"},
                "/api/principal/console", new String[] {"principal@test.edu"},
                "/api/director/console", new String[] {"director@test.edu"},
                "/api/admin/overview", new String[] {"admin@test.edu"});
        String[] everyone = {"plain@test.edu", "hod.cse@test.edu", "principal@test.edu", "director@test.edu", "admin@test.edu"};
        for (var e : allowedOnly.entrySet()) {
            http.get(null, e.getKey()).andExpect(status().isUnauthorized());
            for (String who : everyone) {
                boolean allowed = List.of(e.getValue()).contains(who);
                http.get(http.login(who), e.getKey()).andExpect(allowed ? status().isOk() : status().isForbidden());
            }
        }
    }

    @Test
    void theOverviewKnowsOnlyTheRolesAndStagesOfTheTwoLevelChain() throws Exception {
        db.withdrawnAccount("dean@test.edu", "DEAN");
        db.withdrawnAccount("vp@test.edu", "VICE_PRINCIPAL");
        JsonNode o = get("admin@test.edu", "/api/admin/overview");
        List<String> roles = new ArrayList<>();
        o.get("accounts").forEach(r -> roles.add(r.get("role").asText()));
        assertEquals(List.of("FACULTY", "HOD", "PRINCIPAL", "DIRECTOR", "ADMIN"), roles);
        List<String> stages = new ArrayList<>();
        o.get("pipeline").fieldNames().forEachRemaining(stages::add);
        assertEquals(List.of("NOT_STARTED", "DRAFT", "SUBMITTED", "HOD_REVIEW", "HOD_APPROVED", "PRINCIPAL_REVIEW", "APPROVED"), stages);
        // nothing is asked of the withdrawn levels, and their closed accounts do not hold a healthy set-up back
        Map<String, String> l = levels(o);
        assertFalse(l.containsKey("DEAN_COVERAGE") || l.containsKey("VICE_PRINCIPAL"));
        assertFalse(l.containsValue("problem"), l.toString());
    }
}
