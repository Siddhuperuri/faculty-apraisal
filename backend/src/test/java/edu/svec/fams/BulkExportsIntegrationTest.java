package edu.svec.fams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.svec.fams.appraisal.AppraisalService;
import edu.svec.fams.auth.FamsUserPrincipal;
import edu.svec.fams.auth.Role;
import edu.svec.fams.auth.SmtpMailer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Choosing several rows and acting on them: the appraisal-details export for Heads of Department and administrators, and the
 * administrator's user export and password-reset links. Every rule is checked on the server, with ids sent as a hostile
 * browser would send them.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "FAMS_PUBLIC_URL=https://appraisal.test.example")
class BulkExportsIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestDb db;
    @Autowired JdbcClient jdbc;
    @Autowired AppraisalService appraisals;
    @MockitoBean SmtpMailer mailer;

    static final String DEPARTMENT_CSE = "Computer Science & Engineering / Information Technology";

    TestHttp http;
    FamsUserPrincipal hodCse, hodEce, admin, principal;
    FamsUserPrincipal withContent, draft, notStarted, otherDept;
    MockHttpSession hodSession, adminSession;

    @BeforeEach
    void setUp() throws Exception {
        db.reset();
        when(mailer.configured()).thenReturn(true);
        http = new TestHttp(mvc, json);
        hodCse = db.hod("hod.cse@test.edu", "CSE");
        hodEce = db.hod("hod.ece@test.edu", "ECE");
        admin = db.user("admin@test.edu", Role.ADMIN);
        principal = db.user("principal@test.edu", Role.PRINCIPAL);

        withContent = db.faculty("with@test.edu", "E-WITH", "CSE", "ASST_PROF");
        draft = db.faculty("draft@test.edu", "E-DRAFT", "CSE", "PROFESSOR");
        notStarted = db.faculty("none@test.edu", "E-NONE", "CSE", "LECTURER");
        otherDept = db.faculty("ece@test.edu", "E-ECE", "ECE", "ASST_PROF");

        long a = submitted(withContent, "HOD_REVIEW");
        jdbc.sql("""
                INSERT INTO teaching_courses (appraisal_id, course_code, course_name, course_type, program, branch, semester, hours_per_week, pass_percentage)
                VALUES (?, 'CS301', 'Data Structures', 'THEORY', 'B_TECH', 'CSE', 3, 4, 91.5)""").param(a).update();
        jdbc.sql("""
                INSERT INTO teaching_courses (appraisal_id, course_code, course_name, course_type, program, branch, semester, hours_per_week, pass_percentage)
                VALUES (?, 'CS302', 'Operating Systems', 'LAB', 'B_TECH', 'CSE', 4, 3, 88)""").param(a).update();
        jdbc.sql("INSERT INTO certifications (appraisal_id, platform, title, duration_weeks) VALUES (?, 'NPTEL', 'Machine Learning', 12)").param(a).update();
        jdbc.sql("INSERT INTO fdps (appraisal_id, title, mode, institution_venue, days) VALUES (?, 'AI for teachers', 'ONLINE', 'IIT Madras', 5)").param(a).update();
        jdbc.sql("""
                INSERT INTO events (appraisal_id, activity_type, role, title, start_date, end_date, beneficiaries)
                VALUES (?, 'WORKSHOP', 'COORDINATOR', 'Python workshop', '2025-09-10', '2025-09-12', 60)""").param(a).update();
        jdbc.sql("""
                INSERT INTO journal_publications (appraisal_id, title, author_position, journal, month_year, indexing)
                VALUES (?, 'A study of graphs', 'FIRST', 'J. Graph Theory', '2025-07', 'SCOPUS')""").param(a).update();
        jdbc.sql("INSERT INTO administrative_roles (appraisal_id, scope, role, from_date, to_date) VALUES (?, 'DEPARTMENT', 'Timetable in-charge', '2025-06-01', '2026-05-31')")
                .param(a).update();
        jdbc.sql("""
                INSERT INTO review_actions (appraisal_id, actor_id, actor_role, action, from_status, to_status, comment)
                VALUES (?, ?, 'HOD', 'HOD_START_REVIEW', 'SUBMITTED', 'HOD_REVIEW', 'Please add the lab results.')""")
                .params(a, hodCse.id()).update();

        appraisals.create(draft);                       // a draft, with a course in it that must not leave the author's hands
        jdbc.sql("""
                INSERT INTO teaching_courses (appraisal_id, course_code, course_name, course_type, program, branch, semester, hours_per_week, pass_percentage)
                SELECT id, 'SECRET1', 'Private draft course', 'THEORY', 'B_TECH', 'CSE', 1, 1, 1 FROM appraisals
                WHERE faculty_id = (SELECT id FROM faculty_profiles WHERE user_id = ?)""").param(draft.id()).update();
        submitted(otherDept, "SUBMITTED");

        hodSession = http.login("hod.cse@test.edu");
        adminSession = http.login("admin@test.edu");
    }

    private long submitted(FamsUserPrincipal f, String status) {
        long id = appraisals.create(f);
        jdbc.sql("UPDATE appraisals SET status = ?, submitted_at = '2026-01-15 10:30:00' WHERE id = ?").params(status, id).update();
        return id;
    }

    private long facultyId(FamsUserPrincipal f) {
        return jdbc.sql("SELECT id FROM faculty_profiles WHERE user_id = ?").param(f.id()).query(Long.class).single();
    }

    // ------------------------------------------------------------------ helpers

    private ResultActions hodExport(Object ids, String format) throws Exception {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("facultyIds", ids);
        if (format != null) body.put("format", format);
        return http.post(hodSession, "/api/hod/appraisals/export", body);
    }

    private static String text(ResultActions r) throws Exception {
        String s = new String(r.andReturn().getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        return s.startsWith("﻿") ? s.substring(1) : s;
    }

    /** A small RFC 4180 reader: quoted cells may hold commas, quotes and line breaks. */
    private static List<List<String>> csv(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\r') {
                continue;
            } else if (c == '\n') {
                row.add(cell.toString());
                cell.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                cell.append(c);
            }
        }
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row);
        }
        return rows;
    }

    private static Map<String, byte[]> unzip(byte[] bytes) throws IOException {
        Map<String, byte[]> out = new java.util.LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) out.put(e.getName(), zip.readAllBytes());
        }
        return out;
    }

    private static final List<String> HEADERS = List.of("S.No.", "Name of the Faculty", "Employee ID", "Department", "Designation / Cadre",
            "Date of Joining", "Academics", "NPTEL and other Global Certifications", "FDPs Attended",
            "FDPs / Guest Lectures / Workshops Organized", "Journals / Conferences / Other Patents",
            "Contributions to the College / Department and Other Remarks", "Appraisal Status", "Submitted Date", "HoD Remarks");

    private static List<String> rowFor(List<List<String>> table, String employeeId) {
        return table.stream().filter(r -> r.size() > 2 && r.get(2).equals(employeeId)).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------ HoD: appraisal details

    @Test
    void anHodExportsTheirFacultyInTheDepartmentReviewLayout() throws Exception {
        ResultActions r = hodExport(List.of(facultyId(withContent), facultyId(draft), facultyId(notStarted)), "csv");
        r.andExpect(status().isOk());
        MockHttpServletResponse res = r.andReturn().getResponse();
        assertTrue(res.getContentType().startsWith("text/csv"));
        assertTrue(res.getHeader("Content-Disposition").contains("attachment"));
        assertTrue(res.getHeader("Content-Disposition").contains(".csv"));

        List<List<String>> t = csv(text(r));
        // the title and the subtitle come first in a workbook; a CSV file is the header row and the data
        assertEquals(HEADERS, t.get(0));
        assertEquals(4, t.size());                                    // header + three faculty
        List<String> w = rowFor(t, "E-WITH");
        assertEquals("Dr. E-WITH", w.get(1));
        assertEquals(DEPARTMENT_CSE, w.get(3));
        assertEquals("With HoD", w.get(12));
        assertEquals("2026-01-15", w.get(13));
        assertEquals("Please add the lab results.", w.get(14));
        assertTrue(w.get(6).contains("CS301 - Data Structures"), w.get(6));
        assertTrue(w.get(6).contains("CS302 - Operating Systems"), w.get(6));
        assertTrue(w.get(6).startsWith("1. "), "several records become a numbered list in one cell");
        assertTrue(w.get(7).contains("NPTEL: Machine Learning"), w.get(7));
        assertTrue(w.get(7).contains("12 weeks"), w.get(7));
        assertTrue(w.get(8).contains("AI for teachers"), w.get(8));
        assertTrue(w.get(9).contains("Python workshop"), w.get(9));
        assertTrue(w.get(10).contains("Journal: A study of graphs"), w.get(10));
        assertTrue(w.get(11).contains("Timetable in-charge"), w.get(11));
    }

    @Test
    void aDraftAndANonStarterAreListedButTheirContentIsNeverExported() throws Exception {
        ResultActions r = hodExport(List.of(facultyId(draft), facultyId(notStarted)), "csv");
        r.andExpect(status().isOk());
        String body = text(r);
        assertFalse(body.contains("SECRET1") || body.contains("Private draft course"), "a draft's content stays with its author");
        List<List<String>> t = csv(body);
        for (String id : List.of("E-DRAFT", "E-NONE")) {
            List<String> row = rowFor(t, id);
            assertEquals("Not submitted", row.get(12));
            for (int c : new int[] {6, 7, 8, 9, 10, 11, 13, 14}) assertEquals("—", row.get(c), "column " + c);
        }
    }

    @Test
    void anHodCannotExportSomeoneOutsideTheirDepartmentsEvenByChangingTheIds() throws Exception {
        long outsider = facultyId(otherDept);
        hodExport(List.of(outsider), "csv").andExpect(status().isNotFound());
        hodExport(List.of(facultyId(withContent), outsider), "xlsx").andExpect(status().isNotFound());   // one bad id refuses all
        hodExport(List.of(987654321L), "csv").andExpect(status().isNotFound());                          // unknown: the same answer
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM audit_logs WHERE action = 'EXPORT_APPRAISALS'").query(Integer.class).single());

        // the other department's own HoD can export them, and not the first department's people
        MockHttpSession ece = http.login("hod.ece@test.edu");
        http.post(ece, "/api/hod/appraisals/export", Map.of("facultyIds", List.of(outsider), "format", "csv")).andExpect(status().isOk());
        http.post(ece, "/api/hod/appraisals/export", Map.of("facultyIds", List.of(facultyId(withContent)), "format", "csv"))
                .andExpect(status().isNotFound());
    }

    @Test
    void theRequestMustBeAWellFormedChoice() throws Exception {
        long id = facultyId(withContent);
        hodExport(List.of(), "csv").andExpect(status().isBadRequest());
        hodExport(null, "csv").andExpect(status().isBadRequest());
        hodExport("1", "csv").andExpect(status().isBadRequest());
        hodExport(List.of("x"), "csv").andExpect(status().isBadRequest());
        hodExport(List.of(-4), "csv").andExpect(status().isBadRequest());
        hodExport(List.of(id), null).andExpect(status().isBadRequest());
        hodExport(List.of(id), "pdf").andExpect(status().isBadRequest());
        List<Integer> tooMany = new ArrayList<>();
        for (int i = 1; i <= 2001; i++) tooMany.add(i);
        hodExport(tooMany, "csv").andExpect(status().isBadRequest());
    }

    @Test
    void aRepeatedIdIsOneRowAndTheFileHasNoSecretsInIt() throws Exception {
        long id = facultyId(withContent);
        ResultActions r = hodExport(List.of(id, id, String.valueOf(id)), "csv");
        r.andExpect(status().isOk());
        String body = text(r);
        assertEquals(2, csv(body).size());                                // header + one row
        for (String forbidden : new String[] {"$2a$", "password_hash", "token", "session", TestDb.PASSWORD}) {
            assertFalse(body.toLowerCase().contains(forbidden.toLowerCase()), forbidden);
        }
    }

    @Test
    void onlyAnHodMayUseTheHodExport() throws Exception {
        Map<String, Object> body = Map.of("facultyIds", List.of(facultyId(withContent)), "format", "csv");
        http.post(http.login("with@test.edu"), "/api/hod/appraisals/export", body).andExpect(status().isForbidden());
        http.post(http.login("principal@test.edu"), "/api/hod/appraisals/export", body).andExpect(status().isForbidden());
        http.post(adminSession, "/api/hod/appraisals/export", body).andExpect(status().isForbidden());
    }

    @Test
    void theWorkbookIsARealXlsxWithAFrozenStyledHeaderAndTheYearAndDepartmentInItsTitle() throws Exception {
        ResultActions r = hodExport(List.of(facultyId(withContent), facultyId(notStarted)), "xlsx");
        r.andExpect(status().isOk());
        MockHttpServletResponse res = r.andReturn().getResponse();
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", res.getContentType());
        assertTrue(res.getHeader("Content-Disposition").contains(".xlsx"));
        assertEquals("no-store", res.getHeader("Cache-Control"));

        Map<String, byte[]> parts = unzip(res.getContentAsByteArray());
        assertTrue(parts.keySet().containsAll(Set.of("[Content_Types].xml", "xl/workbook.xml", "xl/styles.xml", "xl/worksheets/sheet1.xml")));
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        for (byte[] xml : parts.values()) assertNotNull(f.newDocumentBuilder().parse(new ByteArrayInputStream(xml)));   // every part is well-formed

        String sheet = new String(parts.get("xl/worksheets/sheet1.xml"), StandardCharsets.UTF_8);
        assertTrue(sheet.contains("state=\"frozen\""), "the header row is frozen");
        assertTrue(sheet.contains(("Faculty appraisal details - 2025-26 - " + DEPARTMENT_CSE).replace("&", "&amp;")), "the title names year and department");
        assertTrue(sheet.contains("Name of the Faculty") && sheet.contains("HoD Remarks"));
        assertTrue(sheet.contains("<cols>") && sheet.contains("customWidth=\"1\""), "the columns are sized");
        String styles = new String(parts.get("xl/styles.xml"), StandardCharsets.UTF_8);
        assertTrue(styles.contains("wrapText=\"1\""));
        assertTrue(styles.contains("dd\\-mmm\\-yyyy"));
        // the submitted date is a real date (a number with the date style), not text
        assertTrue(sheet.matches("(?s).*<c r=\"N\\d+\" s=\"5\"><v>\\d+</v></c>.*"), "the submitted date is an Excel date");
        assertTrue(new String(parts.get("xl/workbook.xml"), StandardCharsets.UTF_8).contains("name=\"Appraisals\""));
    }

    @Test
    void everyExportIsAuditedWithWhoWhatYearDepartmentAndHowMany() throws Exception {
        hodExport(List.of(facultyId(withContent), facultyId(draft)), "xlsx").andExpect(status().isOk());
        var row = jdbc.sql("SELECT actor_id, action, CAST(metadata AS CHAR) FROM audit_logs WHERE action = 'EXPORT_APPRAISALS'")
                .query((rs, n) -> new Object[] {rs.getLong(1), rs.getString(2), rs.getString(3)}).single();
        assertEquals(hodCse.id(), row[0]);
        JsonNode m = json.readTree((String) row[2]);
        assertEquals("2025-26", m.get("academicYear").asText());
        assertEquals("CSE", m.get("departments").get(0).asText());
        assertEquals(2, m.get("selected").asInt());
        assertEquals(1, m.get("withContent").asInt());
        assertEquals("XLSX", m.get("format").asText());
        assertEquals("HOD", m.get("actorRole").asText());
    }

    // ------------------------------------------------------------------ administrator: appraisal details

    @Test
    void anAdministratorMayChooseFacultyByYearDepartmentCadreAndStatusAndExportThem() throws Exception {
        JsonNode all = http.read(http.get(adminSession, "/api/admin/appraisals/roster").andExpect(status().isOk()));
        assertEquals("2025-26", all.get("year").get("name").asText());
        assertEquals(4, all.get("rows").size());

        long cseId = jdbc.sql("SELECT id FROM departments WHERE code = 'CSE'").query(Long.class).single();
        JsonNode cse = http.read(http.get(adminSession, "/api/admin/appraisals/roster?departmentId=" + cseId).andExpect(status().isOk()));
        assertEquals(3, cse.get("rows").size());

        JsonNode notSubmitted = http.read(http.get(adminSession, "/api/admin/appraisals/roster?status=NOT_SUBMITTED").andExpect(status().isOk()));
        Set<String> ids = new HashSet<>();
        notSubmitted.get("rows").forEach(x -> ids.add(x.get("employeeId").asText()));
        assertEquals(Set.of("E-DRAFT", "E-NONE"), ids);                     // a draft is not told apart from nothing at all
        notSubmitted.get("rows").forEach(x -> assertTrue(x.get("status").isNull()));

        long asstProf = jdbc.sql("SELECT id FROM cadres WHERE code = 'ASST_PROF'").query(Long.class).single();
        JsonNode cadre = http.read(http.get(adminSession, "/api/admin/appraisals/roster?cadreId=" + asstProf + "&status=HOD_REVIEW").andExpect(status().isOk()));
        assertEquals(1, cadre.get("rows").size());
        assertEquals("E-WITH", cadre.get("rows").get(0).get("employeeId").asText());
        http.get(adminSession, "/api/admin/appraisals/roster?status=BOGUS").andExpect(status().isBadRequest());
        http.get(adminSession, "/api/admin/appraisals/roster?status=DRAFT").andExpect(status().isBadRequest());

        // exporting two departments at once, across the HoD boundary an HoD could not cross
        ResultActions r = http.post(adminSession, "/api/admin/appraisals/export",
                Map.of("facultyIds", List.of(facultyId(withContent), facultyId(otherDept)), "format", "csv"));
        r.andExpect(status().isOk());
        List<List<String>> t = csv(text(r));
        assertEquals(3, t.size());
        assertEquals("Submitted", rowFor(t, "E-ECE").get(12));
        assertTrue(rowFor(t, "E-WITH").get(6).contains("CS301"), "an administrator may export appraisal content");

        var m = json.readTree(jdbc.sql("SELECT CAST(metadata AS CHAR) FROM audit_logs WHERE action = 'EXPORT_APPRAISALS'")
                .query(String.class).single());
        assertEquals("ADMIN", m.get("actorRole").asText());
        assertEquals(2, m.get("selected").asInt());
        assertEquals(2, m.get("departments").size());
    }

    @Test
    void theAdministratorsAppraisalExportIsForAdministratorsOnly() throws Exception {
        Map<String, Object> body = Map.of("facultyIds", List.of(facultyId(withContent)), "format", "csv");
        http.post(hodSession, "/api/admin/appraisals/export", body).andExpect(status().isForbidden());
        http.get(hodSession, "/api/admin/appraisals/roster").andExpect(status().isForbidden());
        http.post(http.login("with@test.edu"), "/api/admin/appraisals/export", body).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ administrator: users

    @Test
    void anAdministratorExportsTheChosenUsersWithoutAnySecret() throws Exception {
        List<Long> ids = List.of(withContent.id(), hodCse.id(), admin.id());
        ResultActions r = http.post(adminSession, "/api/admin/users/export", Map.of("ids", ids, "format", "csv"));
        r.andExpect(status().isOk());
        String body = text(r);
        List<List<String>> t = csv(body);
        assertEquals(List.of("S.No.", "Name", "Employee ID", "E-mail", "Role", "Status", "Department", "Designation / Cadre", "Last sign-in",
                "Has chosen own password"), t.get(0));
        assertEquals(4, t.size());
        assertTrue(body.contains("with@test.edu") && body.contains("hod.cse@test.edu"));
        assertTrue(body.contains("Head of the Department") && body.contains("Administrator"));
        for (String forbidden : new String[] {"$2a$", "password_hash", "session_version", TestDb.PASSWORD}) {
            assertFalse(body.contains(forbidden), forbidden);
        }
        var m = json.readTree(jdbc.sql("SELECT CAST(metadata AS CHAR) FROM audit_logs WHERE action = 'EXPORT_USERS'").query(String.class).single());
        assertEquals(3, m.get("selected").asInt());
        assertEquals("CSV", m.get("format").asText());

        http.post(adminSession, "/api/admin/users/export", Map.of("ids", List.of(withContent.id(), 987654321L), "format", "xlsx"))
                .andExpect(status().isNotFound());
        http.post(hodSession, "/api/admin/users/export", Map.of("ids", ids, "format", "csv")).andExpect(status().isForbidden());
    }

    @Test
    void aUserNameThatLooksLikeAFormulaIsNeutralisedInTheCsv() throws Exception {
        jdbc.sql("UPDATE faculty_profiles SET name = '=HYPERLINK(\"http://x\")' WHERE user_id = ?").param(withContent.id()).update();
        ResultActions r = http.post(adminSession, "/api/admin/users/export", Map.of("ids", List.of(withContent.id()), "format", "csv"));
        assertTrue(text(r).contains("'=HYPERLINK"), "a leading apostrophe stops it running as a formula");
    }

    // ------------------------------------------------------------------ administrator: reset links

    private static String sha256(String t) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(t.getBytes(StandardCharsets.UTF_8)));
    }

    private JsonNode sendLinks(List<Long> ids) throws Exception {
        return http.read(http.post(adminSession, "/api/admin/users/send-reset-links", Map.of("ids", ids)).andExpect(status().isOk()));
    }

    @Test
    void eachActiveUserGetsTheirOwnOneTimeLinkAndNobodyGetsAPassword() throws Exception {
        FamsUserPrincipal other = db.faculty("other@test.edu", "E-OTH", "CSE", "LECTURER");
        FamsUserPrincipal disabled = db.faculty("off@test.edu", "E-OFF", "CSE", "LECTURER");
        db.disable(disabled);
        FamsUserPrincipal failing = db.faculty("fail@test.edu", "E-FAIL", "CSE", "LECTURER");
        doThrow(new IOException("mailbox full")).when(mailer).send(eq("fail@test.edu"), any(), any());

        JsonNode result = sendLinks(List.of(withContent.id(), other.id(), disabled.id(), admin.id(), failing.id(), 987654321L, other.id()));
        assertEquals(6, result.get("requested").asInt());           // the repeated id counts once
        assertEquals(2, result.get("sent").asInt());
        assertEquals(3, result.get("skipped").asInt());             // disabled, yourself, unknown
        assertEquals(1, result.get("failed").asInt());
        assertTrue(result.get("mailReady").asBoolean());
        assertEquals("fail@test.edu", result.get("failedUsers").get(0).get("email").asText());
        String reasons = result.get("skippedUsers").toString();
        assertTrue(reasons.contains("Your own account") && reasons.contains("not active") && reasons.contains("No such user"), reasons);

        ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> bodies = ArgumentCaptor.forClass(String.class);
        verify(mailer, org.mockito.Mockito.times(3)).send(to.capture(), any(), bodies.capture());   // two accepted, one refused
        verify(mailer, never()).send(eq("off@test.edu"), any(), any());
        verify(mailer, never()).send(eq("admin@test.edu"), any(), any());
        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < to.getAllValues().size(); i++) {
            String b = bodies.getAllValues().get(i);
            assertFalse(b.contains(TestDb.PASSWORD) || b.contains("$2a$"), "no password in the message");
            int at = b.indexOf("https://appraisal.test.example/reset-password?token=");
            assertTrue(at >= 0, b);
            tokens.add(b.substring(at + "https://appraisal.test.example/reset-password?token=".length()).split("\\s")[0]);
        }
        assertEquals(3, tokens.size(), "every link is its own");

        // sent users hold a live token (stored as a hash only); the one whose mail failed holds none that works
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM password_reset_tokens WHERE user_id = ? AND used_at IS NULL AND expires_at > CURRENT_TIMESTAMP")
                .param(withContent.id()).query(Integer.class).single());
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM password_reset_tokens WHERE user_id = ? AND used_at IS NULL")
                .param(failing.id()).query(Integer.class).single());
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM password_reset_tokens WHERE user_id IN (?, ?)").params(disabled.id(), admin.id())
                .query(Integer.class).single());
        for (String t : tokens) {
            assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM password_reset_tokens WHERE token_hash = ?").param(t).query(Integer.class).single(),
                    "only a hash is stored");
        }

        // the link works once for its owner, and their current password worked until then
        http.login("with@test.edu");
        String link = tokens.stream().filter(t -> {
            try {
                return jdbc.sql("SELECT user_id FROM password_reset_tokens WHERE token_hash = ?").param(sha256(t)).query(Long.class)
                        .optional().filter(u -> u.equals(withContent.id())).isPresent();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).findFirst().orElseThrow();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/auth/reset-password")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf().asHeader())
                .contentType("application/json").content(json.writeValueAsString(Map.of("token", link, "newPassword", "Brand-new-pass-2026!"))))
                .andExpect(status().isOk());

        // audited: one entry for each link sent, and one summary with the counts
        assertEquals(2, jdbc.sql("SELECT COUNT(*) FROM audit_logs WHERE action = 'PASSWORD_RESET_LINK_SENT'").query(Integer.class).single());
        var summary = json.readTree(jdbc.sql("SELECT CAST(metadata AS CHAR) FROM audit_logs WHERE action = 'PASSWORD_RESET_LINKS_BULK'")
                .query(String.class).single());
        assertEquals(2, summary.get("sent").asInt());
        assertEquals(1, summary.get("failed").asInt());
        for (String meta : jdbc.sql("SELECT COALESCE(CAST(metadata AS CHAR), '') FROM audit_logs").query(String.class).list()) {
            assertFalse(meta.contains("Brand-new-pass") || meta.contains("$2a$") || meta.contains(tokens.iterator().next()), meta);
        }
    }

    @Test
    void whenTheServerCannotSendMailNothingIsIssuedAndTheAnswerSaysSo() throws Exception {
        when(mailer.configured()).thenReturn(false);
        JsonNode result = sendLinks(List.of(withContent.id(), draft.id()));
        assertFalse(result.get("mailReady").asBoolean());
        assertEquals(0, result.get("sent").asInt());
        assertEquals(2, result.get("failed").asInt());
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM password_reset_tokens").query(Integer.class).single());
        verify(mailer, never()).send(any(), any(), any());
    }

    @Test
    void aUserIsNotFloodedAndTheAdministratorIsRateLimited() throws Exception {
        JsonNode last = null;
        for (int i = 0; i < 4; i++) last = sendLinks(List.of(withContent.id()));
        assertEquals(0, last.get("sent").asInt());                  // the fourth in a window is held back
        assertEquals(1, last.get("skipped").asInt());
        assertTrue(last.get("skippedUsers").toString().contains("several links"));

        for (int i = 4; i < 12; i++) sendLinks(List.of(notStarted.id()));
        http.post(adminSession, "/api/admin/users/send-reset-links", Map.of("ids", List.of(notStarted.id())))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void theLinkRequestIsCheckedAndOnlyForAdministrators() throws Exception {
        http.post(adminSession, "/api/admin/users/send-reset-links", Map.of()).andExpect(status().isBadRequest());
        http.post(adminSession, "/api/admin/users/send-reset-links", Map.of("ids", List.of())).andExpect(status().isBadRequest());
        http.post(adminSession, "/api/admin/users/send-reset-links", Map.of("ids", List.of("x"))).andExpect(status().isBadRequest());
        List<Integer> tooMany = new ArrayList<>();
        for (int i = 1; i <= 201; i++) tooMany.add(i);
        http.post(adminSession, "/api/admin/users/send-reset-links", Map.of("ids", tooMany)).andExpect(status().isBadRequest());
        http.post(hodSession, "/api/admin/users/send-reset-links", Map.of("ids", List.of(withContent.id()))).andExpect(status().isForbidden());
        // the old way of setting many passwords back at once is gone
        http.post(adminSession, "/api/admin/users/reset-passwords", Map.of("ids", List.of(withContent.id()))).andExpect(status().is4xxClientError());
        verify(mailer, never()).send(any(), any(), any());
    }

    @Test
    void theSingleUserActionsAreUnchanged() throws Exception {
        JsonNode issued = http.read(http.post(adminSession, "/api/admin/users/" + withContent.id() + "/reset-password", null).andExpect(status().isOk()));
        assertTrue(issued.has("temporaryPassword"));
        http.get(adminSession, "/api/admin/users").andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(8));
    }
}
