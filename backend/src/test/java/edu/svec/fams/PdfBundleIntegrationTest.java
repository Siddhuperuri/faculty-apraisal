package edu.svec.fams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.svec.fams.auth.FamsUserPrincipal;
import edu.svec.fams.auth.Role;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The printed forms of many appraisals as one zip file, from each console, and the administrator's reset of several
 * passwords at once. What each reader may open is the rule for one form, unchanged: the zip adds no way around it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PdfBundleIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestDb db;
    @Autowired JdbcClient jdbc;

    FamsUserPrincipal f1, f2, f3, otherDept, hod, otherHod, principal, director, admin;
    long a1, a3;   // f1 (CSE) submitted and forwarded, f3 (ECE) submitted; f2 (CSE) has a draft

    @BeforeEach
    void setUp() throws Exception {
        db.reset();
        f1 = db.faculty("f1@test.edu", "E001", "CSE", "ASST_PROF");
        f2 = db.faculty("f2@test.edu", "E002", "CSE", "PROFESSOR");
        f3 = db.faculty("f3@test.edu", "E003", "ECE", "ASST_PROF");
        otherDept = f3;
        hod = db.hod("hod.cse@test.edu", "CSE");
        otherHod = db.hod("hod.ece@test.edu", "ECE");
        principal = db.user("principal@test.edu", Role.PRINCIPAL);
        director = db.user("director@test.edu", Role.DIRECTOR);
        admin = db.user("admin@test.edu", Role.ADMIN);

        a1 = begin(f1);
        begin(f2);                                       // stays a draft
        a3 = begin(f3);
        for (long id : new long[] {a1, a3}) {
            db.complete(id);
            call(f1.id() == owner(id) ? f1 : f3, "POST", "/api/appraisals/" + id + "/submit", Map.of("declarationAccepted", true)).andExpect(status().isOk());
        }
        call(hod, "POST", "/api/appraisals/" + a1 + "/review/start", null).andExpect(status().isOk());
        call(hod, "POST", "/api/appraisals/" + a1 + "/review/approve", Map.of("comment", "Recommended")).andExpect(status().isOk());
    }

    private long begin(FamsUserPrincipal who) throws Exception {
        String created = call(who, "POST", "/api/appraisals", null).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return Long.parseLong(created.replaceAll("\\D+", ""));
    }

    private long owner(long appraisal) {
        return jdbc.sql("SELECT fp.user_id FROM appraisals a JOIN faculty_profiles fp ON fp.id = a.faculty_id WHERE a.id = ?")
                .param(appraisal).query(Long.class).single();
    }

    private long facultyRecord(FamsUserPrincipal who) {
        return jdbc.sql("SELECT id FROM faculty_profiles WHERE user_id = ?").param(who.id()).query(Long.class).single();
    }

    private ResultActions call(FamsUserPrincipal who, String method, String path, Object body) throws Exception {
        var req = method.equals("GET") ? get(path) : post(path);
        req = req.with(user(who)).with(csrf().asHeader());
        if (body != null) req = req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return mvc.perform(req);
    }

    private static List<String> entries(MockHttpServletResponse r) throws Exception {
        List<String> names = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(r.getContentAsByteArray()))) {
            for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                assertTrue(zip.readAllBytes().length > 100, e.getName() + " is a real document");
                names.add(e.getName());
            }
        }
        return names;
    }

    // ---- the zips ----

    @Test
    void theHodGetsTheSubmittedFormsOfTheirDepartmentAndNoDraft() throws Exception {
        MockHttpServletResponse all = call(hod, "POST", "/api/hod/appraisals/pdfs", Map.of("all", true)).andExpect(status().isOk())
                .andReturn().getResponse();
        assertEquals("application/zip", all.getContentType());
        assertEquals(List.of("Appraisal-2025-26-E001.pdf"), entries(all));
        assertEquals("1", all.getHeader("X-Pdf-Included"));

        // selected faculty: the draft's owner is asked for and nothing of theirs is in the zip
        MockHttpServletResponse chosen = call(hod, "POST", "/api/hod/appraisals/pdfs",
                Map.of("facultyIds", List.of(facultyRecord(f1), facultyRecord(f2)))).andExpect(status().isOk()).andReturn().getResponse();
        assertEquals(List.of("Appraisal-2025-26-E001.pdf"), entries(chosen));
    }

    @Test
    void anAppraisalOfAnotherDepartmentIsNeverInAHodsZip() throws Exception {
        call(hod, "POST", "/api/hod/appraisals/pdfs", Map.of("appraisalIds", List.of(a3))).andExpect(status().isConflict());
        MockHttpServletResponse mixed = call(hod, "POST", "/api/hod/appraisals/pdfs", Map.of("appraisalIds", List.of(a1, a3)))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertEquals(List.of("Appraisal-2025-26-E001.pdf"), entries(mixed));
        assertEquals("1", mixed.getHeader("X-Pdf-Skipped"));
        // and the other department's HoD gets their own
        assertEquals(List.of("Appraisal-2025-26-E003.pdf"),
                entries(call(otherHod, "POST", "/api/hod/appraisals/pdfs", Map.of("all", true)).andExpect(status().isOk()).andReturn().getResponse()));
    }

    @Test
    void thePrincipalAndTheDirectorGetOnlyWhatTheHodForwarded() throws Exception {
        for (var who : List.of(principal, director)) {
            String url = who == principal ? "/api/principal/appraisals/pdfs" : "/api/director/appraisals/pdfs";
            assertEquals(List.of("Appraisal-2025-26-E001.pdf"),
                    entries(call(who, "POST", url, Map.of("all", true)).andExpect(status().isOk()).andReturn().getResponse()));
            // an appraisal the HoD has not forwarded cannot be asked for by id
            call(who, "POST", url, Map.of("appraisalIds", List.of(a3))).andExpect(status().isConflict());
        }
    }

    @Test
    void theAdministratorGetsEverySubmittedFormAndNoDraft() throws Exception {
        MockHttpServletResponse all = call(admin, "POST", "/api/admin/appraisals/pdfs", Map.of("all", true)).andExpect(status().isOk())
                .andReturn().getResponse();
        List<String> names = entries(all);
        assertEquals(2, names.size());
        assertTrue(names.contains("Appraisal-2025-26-E001.pdf") && names.contains("Appraisal-2025-26-E003.pdf"));

        MockHttpServletResponse chosen = call(admin, "POST", "/api/admin/appraisals/pdfs",
                Map.of("facultyIds", List.of(facultyRecord(f3), facultyRecord(f2)))).andExpect(status().isOk()).andReturn().getResponse();
        assertEquals(List.of("Appraisal-2025-26-E003.pdf"), entries(chosen));
        assertNotNull(jdbc.sql("SELECT id FROM audit_logs WHERE action = 'EXPORT_APPRAISAL_PDFS' LIMIT 1").query(Long.class).optional().orElse(null));
    }

    @Test
    void everyoneElseIsRefusedAndTheSelectionIsChecked() throws Exception {
        call(f1, "POST", "/api/hod/appraisals/pdfs", Map.of("all", true)).andExpect(status().isForbidden());
        call(hod, "POST", "/api/admin/appraisals/pdfs", Map.of("all", true)).andExpect(status().isForbidden());
        call(principal, "POST", "/api/hod/appraisals/pdfs", Map.of("all", true)).andExpect(status().isForbidden());
        call(hod, "POST", "/api/hod/appraisals/pdfs", Map.of()).andExpect(status().isBadRequest());
        call(hod, "POST", "/api/hod/appraisals/pdfs", Map.of("appraisalIds", List.of())).andExpect(status().isBadRequest());
        call(hod, "POST", "/api/hod/appraisals/pdfs", Map.of("appraisalIds", List.of("x"))).andExpect(status().isBadRequest());
    }

    // ---- remarks in the printed form ----

    @Test
    void theHodsCopyOfAnApprovedFormLeavesOutThePrincipalsRemarksAndTheFacultyCopyLeavesOutAll() throws Exception {
        call(principal, "POST", "/api/appraisals/" + a1 + "/review/start", null).andExpect(status().isOk());
        call(principal, "POST", "/api/appraisals/" + a1 + "/review/approve", Map.of("comment", "Principal secret remark")).andExpect(status().isOk());
        String forPrincipal = text(principal);
        String forHod = text(hod);
        String forFaculty = text(f1);
        assertTrue(forPrincipal.contains("Principal secret remark") && forPrincipal.contains("Recommended"));
        assertTrue(forHod.contains("Recommended") && !forHod.contains("Principal secret remark"));
        assertTrue(!forFaculty.contains("Recommended") && !forFaculty.contains("Principal secret remark"));
    }

    private String text(FamsUserPrincipal who) throws Exception {
        byte[] pdf = call(who, "GET", "/api/appraisals/" + a1 + "/report.pdf", null).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        com.lowagie.text.pdf.PdfReader reader = new com.lowagie.text.pdf.PdfReader(pdf);
        try {
            var extractor = new com.lowagie.text.pdf.parser.PdfTextExtractor(reader);
            StringBuilder all = new StringBuilder();
            for (int p = 1; p <= reader.getNumberOfPages(); p++) all.append(extractor.getTextFromPage(p)).append('\n');
            return all.toString();
        } finally {
            reader.close();
        }
    }

    // ---- several passwords at once ----

    @Test
    void theAdministratorResetsSeveralPasswordsAtOnceAndNeverTheirOwn() throws Exception {
        long before = jdbc.sql("SELECT session_version FROM users WHERE id = ?").param(f1.id()).query(Long.class).single();
        call(admin, "POST", "/api/admin/users/reset-passwords", Map.of("ids", List.of(f1.id(), f2.id(), admin.id(), 999_999L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requested").value(4))
                .andExpect(jsonPath("$.resetCount").value(2))
                .andExpect(jsonPath("$.reset.length()").value(2))
                .andExpect(jsonPath("$.skipped.length()").value(2))
                .andExpect(jsonPath("$.temporaryPassword").isNotEmpty());
        assertEquals(before + 1, jdbc.sql("SELECT session_version FROM users WHERE id = ?").param(f1.id()).query(Long.class).single());
        assertTrue(jdbc.sql("SELECT must_change_password FROM users WHERE id = ?").param(f2.id()).query(Boolean.class).single());
        assertEquals(2, jdbc.sql("SELECT COUNT(*) FROM audit_logs WHERE action = 'PASSWORD_RESET'").query(Integer.class).single());
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM audit_logs WHERE action = 'PASSWORD_RESET_BULK'").query(Integer.class).single());

        call(hod, "POST", "/api/admin/users/reset-passwords", Map.of("ids", List.of(f1.id()))).andExpect(status().isForbidden());
        call(admin, "POST", "/api/admin/users/reset-passwords", Map.of()).andExpect(status().isBadRequest());
    }

    // ---- the analytics figures ----

    @Test
    void theAnalyticsCarryUserCountsGroupedByRoleStateDepartmentAndDesignation() throws Exception {
        String body = call(admin, "GET", "/api/admin/analytics", null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var groups = json.readTree(body).get("userGroups");
        int faculty = 0;
        for (var g : groups) {
            assertTrue(g.has("role") && g.has("status") && g.has("count"));
            assertTrue(!g.has("email") && !g.has("name"), "only counts leave the analytics");
            if ("FACULTY".equals(g.get("role").asText())) faculty += g.get("count").asInt();
        }
        assertEquals(3, faculty);
    }
}
