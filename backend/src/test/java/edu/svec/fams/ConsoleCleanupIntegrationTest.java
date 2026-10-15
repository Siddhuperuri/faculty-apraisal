package edu.svec.fams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import edu.svec.fams.section.FieldSpec;
import edu.svec.fams.section.Sections;
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
 * The form clean-up of 2026-10-13: teaching experience is mandatory (0 to 1,000), a journal paper needs a valid DOI or ISSN,
 * a conference paper no longer has a citation count, a project's amount follows its status, books are "Book Chapter",
 * an outreach role can be "Other" with a typed role, and the faculty member is shown no review history.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ConsoleCleanupIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestDb db;
    @Autowired JdbcClient jdbc;
    @Autowired AppraisalService appraisals;

    FamsUserPrincipal faculty;
    long id;

    @BeforeEach
    void setUp() {
        db.reset();
        faculty = db.faculty("f1@test.edu", "E001", "CSE", "ASST_PROF");
        id = appraisals.create(faculty);
    }

    private static Map<String, Object> rec(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private ResultActions save(String section, Map<String, Object> record) throws Exception {
        return mvc.perform(put("/api/appraisals/" + id + "/sections/" + section).with(user(faculty)).with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("records", List.of(record)))));
    }

    private JsonNode meta(String section, String field) throws Exception {
        JsonNode all = json.readTree(mvc.perform(get("/api/sections/meta").with(user(faculty))).andReturn().getResponse().getContentAsString());
        for (JsonNode s : all) {
            if (!s.get("key").asText().equals(section)) {
                continue;
            }
            for (JsonNode f : s.get("fields")) {
                if (f.get("name").asText().equals(field)) {
                    return f;
                }
            }
        }
        throw new AssertionError(section + "." + field);
    }

    // ---- 2. General information ----

    private Map<String, Object> general(Object experience) {
        return rec("phdStatus", "NOT_APPLICABLE", "teachingExperienceYears", experience);
    }

    @Test
    void teachingExperienceIsWholeYearsFromZeroToFortyAndWholeMonthsFromZeroToEleven() throws Exception {
        assertTrue(meta("general-information", "teachingExperienceYears").get("required").asBoolean());
        assertEquals(40, meta("general-information", "teachingExperienceYears").get("max").asInt());
        assertEquals(11, meta("general-information", "teachingExperienceMonths").get("max").asInt());
        save("general-information", general(null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors['records[0].teachingExperienceYears']").value("Teaching experience (years) is required."));
        save("general-information", general("")).andExpect(status().isBadRequest());
        save("general-information", general("abc")).andExpect(status().isBadRequest());
        save("general-information", general(-1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors['records[0].teachingExperienceYears']")
                        .value("Teaching experience (years) must be between 0 and 40."));
        save("general-information", general(41)).andExpect(status().isBadRequest());
        save("general-information", general(6.5)).andExpect(status().isBadRequest());     // whole years only

        save("general-information", rec("phdStatus", "NOT_APPLICABLE", "teachingExperienceYears", 5, "teachingExperienceMonths", 12))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors['records[0].teachingExperienceMonths']")
                        .value("Teaching experience (months) must be between 0 and 11."));
        save("general-information", rec("phdStatus", "NOT_APPLICABLE", "teachingExperienceYears", 5, "teachingExperienceMonths", -1))
                .andExpect(status().isBadRequest());

        save("general-information", general(0)).andExpect(status().isOk());
        save("general-information", rec("phdStatus", "NOT_APPLICABLE", "teachingExperienceYears", 40, "teachingExperienceMonths", 11))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].teachingExperienceYears").value(40))
                .andExpect(jsonPath("$.records[0].teachingExperienceMonths").value(11));
        assertEquals(40, jdbc.sql("SELECT teaching_experience_years FROM general_information WHERE appraisal_id = ?")
                .param(id).query(Integer.class).single());
    }

    @Test
    void anAppraisalWithoutTeachingExperienceCannotBeSubmitted() throws Exception {
        db.complete(id);
        jdbc.sql("UPDATE general_information SET teaching_experience_years = NULL WHERE appraisal_id = ?").param(id).update();
        mvc.perform(get("/api/appraisals/" + id).with(user(faculty)))
                .andExpect(jsonPath("$.submitBlockers[0]").value(org.hamcrest.Matchers.containsString("teaching experience")));
        mvc.perform(post("/api/appraisals/" + id + "/submit").with(user(faculty)).with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON).content("{\"declarationAccepted\":true}")).andExpect(status().isBadRequest());
        jdbc.sql("UPDATE general_information SET teaching_experience_years = 12 WHERE appraisal_id = ?").param(id).update();
        mvc.perform(get("/api/appraisals/" + id).with(user(faculty))).andExpect(jsonPath("$.submitBlockers.length()").value(0));
    }

    // ---- 3. Research and publications ----

    private Map<String, Object> journal(Object doi) {
        Map<String, Object> m = rec("title", "T", "authorPosition", "1", "journal", "J", "monthYear", "2026-02", "indexing", "SCOPUS");
        if (doi != null) m.put("doiIssn", doi);
        return m;
    }

    @Test
    void aJournalPaperNeedsAValidDoiOrIssn() throws Exception {
        assertTrue(meta("journal-publications", "doiIssn").get("required").asBoolean());
        assertEquals("DOI_ISSN", meta("journal-publications", "doiIssn").get("format").asText());
        save("journal-publications", journal(null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors['records[0].doiIssn']").value("DOI or ISSN is required."));
        save("journal-publications", journal("   ")).andExpect(status().isBadRequest());
        for (String bad : new String[] {"n/a", "none", "12345678", "0378-5954", "10.12/short", "doi", "ISSN:"}) {
            save("journal-publications", journal(bad)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors['records[0].doiIssn']")
                            .value("DOI or ISSN must include a valid DOI (such as 10.1000/xyz123) or ISSN (such as 1234-5678)."));
        }
        // a valid DOI, an ISSN (whose check digit may be X), either written with its label, or both
        for (String good : new String[] {"10.1000/xyz123", "0378-5955", "2434-561X", "doi:10.1016/j.cell.2020.01.001",
                "https://doi.org/10.1109/5.771073", "ISSN: 0378-5955", "10.1000/abc, 0378-5955", "see 0378-5955"}) {
            save("journal-publications", journal(good)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.records[0].doiIssn").value(good));
        }
        assertTrue(FieldSpec.hasValidDoiOrIssn("1050-124X"));
        assertFalse(FieldSpec.hasValidDoiOrIssn("1050-124Y"));
        assertFalse(FieldSpec.hasValidDoiOrIssn(null));
    }

    @Test
    void aPaperEnteredBeforeThenIsNamedAtSubmissionAndMustBeCorrectedWhenTheSectionIsSaved() throws Exception {
        db.complete(id);
        jdbc.sql("INSERT INTO journal_publications (appraisal_id, title, author_position, journal, month_year, indexing) VALUES (?, 'Old', '1', 'J', '2026-01', 'SCOPUS')")
                .param(id).update();
        mvc.perform(get("/api/appraisals/" + id).with(user(faculty)))
                .andExpect(jsonPath("$.submitBlockers[0]").value(org.hamcrest.Matchers.containsString("Journal publications")));
        // saving the list as it is now refuses the paper without a DOI or ISSN
        long paper = jdbc.sql("SELECT id FROM journal_publications WHERE appraisal_id = ?").param(id).query(Long.class).single();
        Map<String, Object> same = journal(null);
        same.put("id", paper);
        save("journal-publications", same).andExpect(status().isBadRequest());
        same.put("doiIssn", "0378-5955");
        save("journal-publications", same).andExpect(status().isOk());
        mvc.perform(get("/api/appraisals/" + id).with(user(faculty))).andExpect(jsonPath("$.submitBlockers.length()").value(0));
    }

    @Test
    void aConferencePaperHasNoCitationCountButOldCountsAreKept() throws Exception {
        assertTrue(Sections.get("conference-papers").fields().stream().noneMatch(f -> f.name().equals("citations")));
        Map<String, Object> paper = rec("title", "Paper", "conference", "ICML", "level", "INTL", "monthYear", "2026-03");
        save("conference-papers", paper).andExpect(status().isOk()).andExpect(jsonPath("$.records[0].citations").doesNotExist());
        // it is not a field any more: sending it is refused, like any other unknown name
        Map<String, Object> withCount = new LinkedHashMap<>(paper);
        withCount.put("citations", 4);
        save("conference-papers", withCount).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors['records[0].citations']").value("Not a recognised field."));

        // a count entered before stays in the database through an edit, and is not shown
        long row = jdbc.sql("SELECT id FROM conference_papers WHERE appraisal_id = ?").param(id).query(Long.class).single();
        jdbc.sql("UPDATE conference_papers SET citations = 7 WHERE id = ?").param(row).update();
        Map<String, Object> edited = new LinkedHashMap<>(paper);
        edited.put("id", row);
        edited.put("venue", "Vienna");
        save("conference-papers", edited).andExpect(status().isOk()).andExpect(jsonPath("$.records[0].venue").value("Vienna"))
                .andExpect(jsonPath("$.records[0].citations").doesNotExist());
        assertEquals(7, jdbc.sql("SELECT citations FROM conference_papers WHERE id = ?").param(row).query(Integer.class).single());
    }

    // ---- 4. Funded projects ----

    private Map<String, Object> project(String status, Object amount) {
        Map<String, Object> m = rec("title", "Edge AI", "role", "PI", "type", "RESEARCH", "fundingAgencyClient", "DST",
                "startDate", "2026-01-01", "endDate", "2026-12-31", "status", status, "year", 2026);
        if (amount != null) m.put("amount", amount);
        return m;
    }

    @Test
    void aSanctionedProjectNeedsAValidAmountAndAnAppliedOneHasNone() throws Exception {
        JsonNode amount = meta("funded-projects", "amount");
        assertFalse(amount.get("required").asBoolean());
        assertEquals("status", amount.get("onlyWhenField").asText());
        assertEquals("SANCTIONED", amount.get("onlyWhenEquals").asText());
        assertTrue(amount.get("onlyWhenDisabled").asBoolean());
        assertEquals("CURRENCY", amount.get("format").asText());

        save("funded-projects", project("SANCTIONED", null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors['records[0].amount']").value("Amount (Rs.) is required when Status is Sanctioned."));
        for (Object bad : new Object[] {-1, "-0.01", "abc", 12.345, "1,000"}) {
            save("funded-projects", project("SANCTIONED", bad)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors['records[0].amount']")
                            .value("Amount (Rs.) must be a valid amount: 0 or more, with at most 2 decimal places."));
        }
        save("funded-projects", project("SANCTIONED", 0)).andExpect(status().isOk());
        save("funded-projects", project("SANCTIONED", "250000.50")).andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].amount").value(250000.5))
                .andExpect(jsonPath("$.summary.totalAmountSanctioned").value(250000.5));

        // applied: nothing may be sent, and nothing is stored
        save("funded-projects", project("APPLIED", 5000)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors['records[0].amount']").value("Amount (Rs.) must be left empty unless Status is Sanctioned."));
        save("funded-projects", project("APPLIED", null)).andExpect(status().isOk()).andExpect(jsonPath("$.records[0].amount").doesNotExist());
        assertNull(jdbc.sql("SELECT amount FROM funded_projects WHERE appraisal_id = ?").param(id).query((rs, n) -> rs.getBigDecimal(1)).list().get(0));
    }

    @Test
    void changingASanctionedProjectToAppliedClearsItsAmountAndAnOldOneIsNotShown() throws Exception {
        save("funded-projects", project("SANCTIONED", 90000)).andExpect(status().isOk());
        long row = jdbc.sql("SELECT id FROM funded_projects WHERE appraisal_id = ?").param(id).query(Long.class).single();
        Map<String, Object> applied = project("APPLIED", null);
        applied.put("id", row);
        save("funded-projects", applied).andExpect(status().isOk()).andExpect(jsonPath("$.records[0].amount").doesNotExist());
        assertNull(jdbc.sql("SELECT amount FROM funded_projects WHERE id = ?").param(row).query((rs, n) -> rs.getBigDecimal(1)).list().get(0));

        // an amount saved against an applied project before the rule is left in the database but never shown or counted
        jdbc.sql("UPDATE funded_projects SET amount = 123 WHERE id = ?").param(row).update();
        mvc.perform(get("/api/appraisals/" + id + "/sections/funded-projects").with(user(faculty)))
                .andExpect(jsonPath("$.records[0].amount").doesNotExist())
                .andExpect(jsonPath("$.summary.totalAmountSanctioned").value(0.0));
    }

    // ---- 5. Books ----

    @Test
    void aChapterIsShownAsBookChapterWhileItsCodeIsKept() throws Exception {
        JsonNode type = meta("books", "type");
        assertEquals("Book Chapter", type.get("labels").get("CHAPTER").asText());
        assertEquals("Book", type.get("labels").get("BOOK").asText());
        assertTrue(type.get("allowed").toString().contains("CHAPTER"));
        save("books", rec("authors", "A", "title", "C", "publisher", "P", "isbn", "9780000000002", "monthYear", "2026-01", "type", "CHAPTER"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.records[0].type").value("CHAPTER")).andExpect(jsonPath("$.summary.chapters").value(1));
    }

    // ---- 6. Outreach ----

    private Map<String, Object> outreach(String roleCode, String other) {
        Map<String, Object> m = rec("roleCode", roleCode, "eventActivity", "Keynote", "organization", "ABC College", "eventDate", "2026-03-09");
        if (other != null) m.put("roleOther", other);
        return m;
    }

    @Test
    void externalExaminerIsProjectExternalExaminerAndOtherTakesATypedRole() throws Exception {
        JsonNode role = meta("outreach", "roleCode");
        assertEquals("Project External Examiner", role.get("labels").get("EXTERNAL_EXAMINER").asText());
        assertTrue(role.get("allowed").toString().contains("OTHER"));
        assertFalse(role.get("allowed").toString().contains("OTHERS"));   // the old closed "Others" is not offered
        JsonNode other = meta("outreach", "roleOther");
        assertEquals("roleCode", other.get("onlyWhenField").asText());
        assertEquals("OTHER", other.get("onlyWhenEquals").asText());
        assertFalse(other.get("onlyWhenDisabled").asBoolean());   // hidden until Other is chosen

        save("outreach", outreach("EXTERNAL_EXAMINER", null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].role").value("Project External Examiner"))
                .andExpect(jsonPath("$.records[0].roleOther").doesNotExist());

        save("outreach", outreach("OTHER", null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors['records[0].roleOther']").value("Other role is required when Role is Other."));
        save("outreach", outreach("OTHER", "   ")).andExpect(status().isBadRequest());
        save("outreach", outreach("OTHER", "Chief Guest")).andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].role").value("Chief Guest"))
                .andExpect(jsonPath("$.records[0].roleOther").value("Chief Guest"))
                .andExpect(jsonPath("$.records[0].roleCode").value("OTHER"));
        assertEquals("Chief Guest", jdbc.sql("SELECT role FROM outreach WHERE appraisal_id = ?").param(id).query(String.class).single());

        // a typed role that goes with another choice is not kept
        save("outreach", outreach("RESOURCE_PERSON", "Chief Guest")).andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].role").value("Resource Person"))
                .andExpect(jsonPath("$.records[0].roleOther").doesNotExist());
    }

    @Test
    void theTypedRoleReachesTheMarksTheReportAndTheExport() throws Exception {
        save("outreach", outreach("OTHER", "Moderator")).andExpect(status().isOk());
        db.complete(id);
        mvc.perform(post("/api/appraisals/" + id + "/submit").with(user(faculty)).with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON).content("{\"declarationAccepted\":true}")).andExpect(status().isOk());
        // the score sheet still counts the entry, whatever its role
        FamsUserPrincipal hod = db.hod("hod@test.edu", "CSE");
        mvc.perform(get("/api/appraisals/" + id).with(user(hod)))
                .andExpect(jsonPath("$.scores[?(@.criterion=='OUTREACH')].breakdown[0].count").value(1));
        byte[] pdf = mvc.perform(get("/api/appraisals/" + id + "/report.pdf").with(user(hod))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        com.lowagie.text.pdf.PdfReader reader = new com.lowagie.text.pdf.PdfReader(pdf);
        StringBuilder text = new StringBuilder();
        try {
            com.lowagie.text.pdf.parser.PdfTextExtractor extractor = new com.lowagie.text.pdf.parser.PdfTextExtractor(reader);
            for (int p = 1; p <= reader.getNumberOfPages(); p++) text.append(extractor.getTextFromPage(p)).append('\n');
        } finally {
            reader.close();
        }
        // and in the HoD's export of appraisal details, as typed
        long facultyId = jdbc.sql("SELECT id FROM faculty_profiles WHERE user_id = ?").param(faculty.id()).query(Long.class).single();
        long yearId = jdbc.sql("SELECT academic_year_id FROM appraisals WHERE id = ?").param(id).query(Long.class).single();
        String csv = mvc.perform(post("/api/hod/appraisals/export").with(user(hod)).with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("facultyIds", List.of(facultyId), "academicYearId", yearId, "format", "csv"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(csv.contains("Outreach: Moderator - Keynote"), csv);
        assertTrue(text.toString().contains("Moderator"), "the typed role is on the printed form");
        assertTrue(text.toString().contains("Project External Examiner"), "the list of roles on the form uses the new wording");
    }

    // ---- 7. No review history for the faculty member ----

    @Test
    void theFacultyMemberIsGivenNoReviewHistoryAndTheReviewersAre() throws Exception {
        db.complete(id);
        mvc.perform(post("/api/appraisals/" + id + "/submit").with(user(faculty)).with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON).content("{\"declarationAccepted\":true}")).andExpect(status().isOk());
        FamsUserPrincipal hod = db.hod("hod@test.edu", "CSE");
        mvc.perform(post("/api/appraisals/" + id + "/review/start").with(user(hod)).with(csrf().asHeader())).andExpect(status().isOk());
        mvc.perform(post("/api/appraisals/" + id + "/review/approve").with(user(hod)).with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"Well done\"}")).andExpect(status().isOk());

        String mine = mvc.perform(get("/api/appraisals/" + id).with(user(faculty))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertFalse(mine.contains("\"history\""), "no history key in the faculty member's view");
        assertFalse(mine.contains("Well done"), "no reviewer comment in the faculty member's view");
        assertFalse(mine.contains("\"actorRole\"") || mine.contains("\"fromStatus\""), "no step of the review in the faculty member's view");

        mvc.perform(get("/api/appraisals/" + id).with(user(hod))).andExpect(jsonPath("$.history.length()").value(3))
                .andExpect(jsonPath("$.history[2].comment").value("Well done"));
        // nothing else in the API serves it either
        for (String path : new String[] {"/history", "/review-history", "/review/history", "/review", "/review-actions"}) {
            int code = mvc.perform(get("/api/appraisals/" + id + path).with(user(faculty))).andReturn().getResponse().getStatus();
            assertTrue(code == 404 || code == 405 || code == 403, path + " answered " + code);
        }
        // the list of the faculty member's own appraisals carries no history either
        String list = mvc.perform(get("/api/appraisals").with(user(faculty))).andReturn().getResponse().getContentAsString();
        assertFalse(list.contains("history") || list.contains("Well done"));
    }
}
