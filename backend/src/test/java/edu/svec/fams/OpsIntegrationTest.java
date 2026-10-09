package edu.svec.fams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.svec.fams.auth.Role;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
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

/** The administrator's operations pages: storage, accounts to tidy, imports, year readiness, restore tests and handover notes. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpsIntegrationTest {

    private static final String HEADING = "Name,College e-mail address,Role,Employee ID,Contact number,Department,Designation (cadre)\n";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestDb db;
    @Autowired JdbcClient jdbc;

    TestHttp http;
    MockHttpSession admin;

    @BeforeEach
    void setUp() throws Exception {
        db.reset();
        http = new TestHttp(mvc, json);
        db.user("admin@test.edu", Role.ADMIN);
        admin = http.login("admin@test.edu");
    }

    private JsonNode get(String path) throws Exception {
        return http.read(http.get(admin, path).andExpect(status().isOk()));
    }

    private static List<String> names(JsonNode array, String field) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.get(field).asText()));
        return out;
    }

    // ---- who may see it ----

    @Test
    void onlyAdministratorsMayOpenAnyOfIt() throws Exception {
        db.faculty("f@test.edu", "F-1", "CSE", "PROFESSOR");
        db.hod("h@test.edu", "CSE");
        for (String who : new String[] {"f@test.edu", "h@test.edu"}) {
            MockHttpSession s = http.login(who);
            for (String path : new String[] {"/api/admin/backup-health", "/api/admin/storage-health", "/api/admin/account-hygiene",
                    "/api/admin/inactive-accounts", "/api/admin/imports", "/api/admin/handover", "/api/admin/restore-tests",
                    "/api/admin/academic-years/1/readiness"}) {
                http.get(s, path).andExpect(status().isForbidden());
            }
        }
        http.get(null, "/api/admin/backup-health").andExpect(status().isUnauthorized());
    }

    // ---- storage ----

    @Test
    void storageHealthMeasuresTheDiskAndExpectsAThreeYearUse() throws Exception {
        db.faculty("a@test.edu", "A-1", "CSE", "PROFESSOR");
        db.faculty("b@test.edu", "B-1", "CSE", "PROFESSOR");
        JsonNode s = get("/api/admin/storage-health");

        assertTrue(s.get("diskTotalBytes").asLong() > 0);
        assertTrue(s.get("diskFreeBytes").asLong() > 0);
        assertEquals(80, s.get("warnPercent").asInt());
        assertEquals(3, s.get("retentionYears").asInt());
        assertEquals(2, s.get("activeFaculty").asInt());
        assertFalse(s.get("averageMeasured").asBoolean());
        assertEquals(250_000, s.get("averageReportBytes").asLong());
        // Two faculty, three years of a typical report each, plus the database for three years.
        assertEquals(2 * 250_000L * 3 + s.get("databaseBytes").asLong() * 3, s.get("expectedBytes").asLong());
        assertFalse(s.get("uploadsBuilt").asBoolean());
        assertTrue(List.of("ok", "warn", "problem").contains(s.get("level").asText()));
    }

    // ---- accounts to tidy ----

    @Test
    void hygieneCountsAndFindsLookAlikes() throws Exception {
        var a = db.faculty("a@test.edu", "EMP-001", "CSE", "PROFESSOR");
        var b = db.faculty("b@test.edu", "emp 001", "CSE", "PROFESSOR");
        var c = db.faculty("c@test.edu", "C-3", "CSE", "PROFESSOR");
        jdbc.sql("UPDATE faculty_profiles SET contact_no = '98765 43210' WHERE user_id = ?").param(a.id()).update();
        jdbc.sql("UPDATE faculty_profiles SET contact_no = '+91 9876543210' WHERE user_id = ?").param(c.id()).update();
        jdbc.sql("UPDATE users SET must_change_password = TRUE WHERE id IN (?, ?)").params(a.id(), b.id()).update();
        db.disable(c);
        db.user("lost@test.edu", Role.FACULTY);          // a faculty account with no profile
        db.user("hod@test.edu", Role.HOD);               // a head with no department

        JsonNode h = get("/api/admin/account-hygiene");

        assertEquals(2, h.get("temporaryPassword").asInt());
        assertEquals(1, h.get("disabled").asInt());
        assertEquals(1, h.get("duplicateEmployeeIds").size());
        assertEquals("EMP001", h.get("duplicateEmployeeIds").get(0).get("value").asText());
        assertEquals(2, h.get("duplicateEmployeeIds").get(0).get("accounts").size());
        // c is disabled, so only active accounts are compared: the two contact numbers belong to a (active) and c (disabled).
        assertEquals(0, h.get("duplicateContacts").size());
        assertEquals(List.of("lost@test.edu", "hod@test.edu"), emails(h.get("missingAssignments")));

        jdbc.sql("UPDATE users SET status = 'ACTIVE' WHERE id = ?").param(c.id()).update();
        assertEquals(1, get("/api/admin/account-hygiene").get("duplicateContacts").size());
    }

    private static List<String> emails(JsonNode gaps) {
        List<String> out = new ArrayList<>();
        gaps.forEach(g -> out.add(g.get("account").get("email").asText()));
        return out;
    }

    @Test
    void inactiveAccountsAreThoseNotUsedForTheChosenPeriod() throws Exception {
        var old = db.faculty("old@test.edu", "O-1", "CSE", "PROFESSOR");
        var recent = db.faculty("recent@test.edu", "R-1", "CSE", "PROFESSOR");
        var neverOld = db.faculty("never@test.edu", "N-1", "CSE", "PROFESSOR");
        jdbc.sql("UPDATE users SET last_login_at = DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 120 DAY) WHERE id = ?").param(old.id()).update();
        jdbc.sql("UPDATE users SET last_login_at = DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 5 DAY) WHERE id = ?").param(recent.id()).update();
        jdbc.sql("UPDATE users SET created_at = DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 200 DAY) WHERE id = ?").param(neverOld.id()).update();

        assertEquals(List.of("never@test.edu", "old@test.edu"), names(get("/api/admin/inactive-accounts?days=90"), "email"));
        assertEquals(List.of("never@test.edu"), names(get("/api/admin/inactive-accounts?days=150"), "email"));
        // The new admin account was only just created and has never signed in through the API: not inactive.
        http.get(admin, "/api/admin/inactive-accounts?days=3").andExpect(status().isBadRequest());
        http.get(admin, "/api/admin/inactive-accounts?days=5000").andExpect(status().isBadRequest());
    }

    // ---- imports ----

    @Test
    void everyImportIsRecordedAndARefusedOneKeepsItsProblems() throws Exception {
        String bad = HEADING + "Asha,asha@svec.edu,Faculty,E-1,,CSE,Professor\nNo Email,,Faculty,E-2,,CSE,Professor\n=Evil,evil@svec.edu,Faculty,E-3,,CSE,Professor\n";
        http.postFile(admin, "/api/admin/users/import", bad.getBytes(StandardCharsets.UTF_8)).andExpect(status().isOk());
        String good = HEADING + "Asha,asha@svec.edu,Faculty,E-1,,CSE,Professor\n";
        http.postFile(admin, "/api/admin/users/import", good.getBytes(StandardCharsets.UTF_8)).andExpect(status().isOk());
        http.postFile(admin, "/api/admin/users/import", "Wrong,Columns\n1,2\n".getBytes(StandardCharsets.UTF_8)).andExpect(status().isBadRequest());

        JsonNode history = get("/api/admin/imports");
        assertEquals(3, history.size());
        // Newest first: the unreadable file, the good one, the refused one.
        assertEquals("REJECTED", history.get(0).get("outcome").asText());
        assertEquals(0, history.get(0).get("rowsInFile").asInt());
        assertEquals("CREATED", history.get(1).get("outcome").asText());
        assertEquals(1, history.get(1).get("createdCount").asInt());
        assertFalse(history.get(1).get("hasReport").asBoolean());
        JsonNode refused = history.get(2);
        assertEquals("REJECTED", refused.get("outcome").asText());
        assertEquals(3, refused.get("rowsInFile").asInt());
        assertEquals(0, refused.get("createdCount").asInt());
        assertEquals(1, refused.get("rejectedRows").asInt());
        assertTrue(refused.get("hasReport").asBoolean());
        assertEquals("admin@test.edu", refused.get("administrator").asText());

        String report = http.get(admin, "/api/admin/imports/" + refused.get("id").asLong() + "/report").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(report.startsWith("Line,Column,Problem\r\n"));
        assertTrue(report.contains("College e-mail address"));
        http.get(admin, "/api/admin/imports/" + history.get(1).get("id").asLong() + "/report").andExpect(status().isNotFound());
    }

    // ---- years ----

    @Test
    void closingAYearListsWhatIsStillOpenAndOpeningACloseOneChecksItsDates() throws Exception {
        long year = jdbc.sql("SELECT id FROM academic_years WHERE name = '2025-26'").query(Long.class).single();
        db.faculty("a@test.edu", "A-1", "CSE", "PROFESSOR");
        db.faculty("b@test.edu", "B-1", "CSE", "PROFESSOR");
        MockHttpSession a = http.login("a@test.edu");
        http.post(a, "/api/appraisals", null).andExpect(status().isCreated());

        JsonNode closing = get("/api/admin/academic-years/" + year + "/readiness");
        assertEquals("CLOSING", closing.get("mode").asText());
        assertEquals(1, closing.get("byState").get("DRAFT").asInt());
        assertEquals(1, closing.get("byState").get("NOT_STARTED").asInt());
        assertFalse(closing.get("ready").asBoolean());
        List<String> codes = names(closing.get("items"), "code");
        assertTrue(codes.containsAll(List.of("POLICIES", "DRAFTS", "REVIEWS", "NOT_STARTED", "NEXT_YEAR")));
        assertEquals("warn", level(closing, "DRAFTS"));
        assertEquals("ok", level(closing, "REVIEWS"));
        assertEquals("warn", level(closing, "NEXT_YEAR"));    // this is the only open year

        jdbc.sql("UPDATE academic_years SET active = FALSE WHERE id = ?").param(year).update();
        JsonNode opening = get("/api/admin/academic-years/" + year + "/readiness");
        assertEquals("OPENING", opening.get("mode").asText());
        assertEquals("warn", level(opening, "EXISTING"));
        http.get(admin, "/api/admin/academic-years/999999/readiness").andExpect(status().isNotFound());
    }

    private static String level(JsonNode readiness, String code) {
        for (JsonNode i : readiness.get("items")) {
            if (i.get("code").asText().equals(code)) {
                return i.get("level").asText();
            }
        }
        throw new AssertionError("no item " + code);
    }

    // ---- restore tests ----

    @Test
    void aRestoreTestIsRecordedWithTheDateAndResult() throws Exception {
        http.post(admin, "/api/admin/restore-tests", Map.of("testedOn", LocalDate.now().plusDays(1).toString(), "result", "PASSED"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.testedOn").exists());
        http.post(admin, "/api/admin/restore-tests", Map.of("testedOn", LocalDate.now().toString(), "result", "MAYBE"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.result").exists());
        http.post(admin, "/api/admin/restore-tests", Map.of("testedOn", "yesterday", "result", "PASSED")).andExpect(status().isBadRequest());

        http.post(admin, "/api/admin/restore-tests", Map.of("testedOn", LocalDate.now().minusDays(40).toString(), "result", "PASSED", "notes", "On the spare PC"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.result").value("PASSED")).andExpect(jsonPath("$.recordedBy").value("admin@test.edu"));
        http.post(admin, "/api/admin/restore-tests", Map.of("testedOn", LocalDate.now().toString(), "result", "FAILED")).andExpect(status().isCreated());

        JsonNode list = get("/api/admin/restore-tests");
        assertEquals(2, list.size());
        assertEquals("FAILED", list.get(0).get("result").asText());           // newest first
        assertEquals(LocalDate.now().minusDays(40).toString(), get("/api/admin/backup-health").get("lastRestoreTest").get("testedOn").asText());
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM audit_logs WHERE action = 'RESTORE_TEST_RECORDED' AND metadata LIKE '%FAILED%'")
                .query(Integer.class).single());
    }

    // ---- handover ----

    @Test
    void handoverNotesAreKeptAndCanBeCleared() throws Exception {
        JsonNode empty = get("/api/admin/handover");
        assertEquals(6, empty.size());
        assertEquals("serverManager", empty.get(0).get("key").asText());
        assertEquals("", empty.get(0).get("value").asText());

        http.put(admin, "/api/admin/handover", Map.of("serverManager", "  Mr. Rao, IT office, ext 214  ", "bogus", "ignored"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].value").value("Mr. Rao, IT office, ext 214"))
                .andExpect(jsonPath("$[0].updatedBy").value("admin@test.edu"));
        http.put(admin, "/api/admin/handover", Map.of("other", "x".repeat(1001))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.other").exists());
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM handover_notes").query(Integer.class).single());

        http.put(admin, "/api/admin/handover", Map.of("serverManager", "")).andExpect(status().isOk()).andExpect(jsonPath("$[0].value").value(""));
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM handover_notes").query(Integer.class).single());
        assertEquals(2, jdbc.sql("SELECT COUNT(*) FROM audit_logs WHERE action = 'HANDOVER_UPDATED'").query(Integer.class).single());
    }
}
