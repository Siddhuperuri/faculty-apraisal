package edu.svec.fams;

import static edu.svec.fams.SectionSchemaTest.rec;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.svec.fams.appraisal.AppraisalService;
import edu.svec.fams.auth.FamsUserPrincipal;
import edu.svec.fams.auth.Role;
import java.util.ArrayList;
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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SectionIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired TestDb db;
    @Autowired JdbcClient jdbc;
    @Autowired AppraisalService appraisals;
    @Autowired ObjectMapper json;

    FamsUserPrincipal faculty, otherFaculty, hod, otherDeptHod, principal, admin;
    long id;

    @BeforeEach
    void setUp() {
        db.reset();
        faculty = db.faculty("f1@test.edu", "E001", "CSE", "ASST_PROF");
        otherFaculty = db.faculty("f2@test.edu", "E002", "CSE", "PROFESSOR");
        hod = db.hod("hod@test.edu", "CSE");
        otherDeptHod = db.hod("hod2@test.edu", "ECE");
        principal = db.user("p@test.edu", Role.PRINCIPAL);
        admin = db.user("admin@test.edu", Role.ADMIN);
        id = appraisals.create(faculty);
    }

    // ---- helpers ----

    private ResultActions read(FamsUserPrincipal who, long appraisal, String key) throws Exception {
        return mvc.perform(get("/api/appraisals/" + appraisal + "/sections/" + key).with(user(who)));
    }

    private ResultActions save(FamsUserPrincipal who, long appraisal, String key, Object records) throws Exception {
        String body = json.writeValueAsString(Map.of("records", records));
        return mvc.perform(put("/api/appraisals/" + appraisal + "/sections/" + key).with(user(who)).with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static Map<String, Object> course(String code, double hours, double pass, Double ph1, Double ph2) {
        Map<String, Object> m = rec("courseCode", code, "courseName", "Course " + code, "courseType", "THEORY",
                "program", "B_TECH", "branch", "CSE", "semester", 3, "hoursPerWeek", hours,
                "passPercentage", pass);
        if (ph1 != null) m.put("phase1Feedback", ph1);
        if (ph2 != null) m.put("phase2Feedback", ph2);
        return m;
    }

    private List<Map<String, Object>> savedRecords(String key) throws Exception {
        String body = read(faculty, id, key).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return records(body);
    }

    /** The "records" of a section response, typed, without an unchecked cast. */
    private List<Map<String, Object>> records(String body) throws Exception {
        return json.convertValue(json.readTree(body).get("records"), new TypeReference<List<Map<String, Object>>>() {});
    }

    private int auditCount(String action) {
        return jdbc.sql("SELECT COUNT(*) FROM audit_logs WHERE action = ? AND entity_id = ?")
                .params(action, id).query(Integer.class).single();
    }

    private int dbRows(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE appraisal_id = ?")
                .param(id).query(Integer.class).single();
    }

    private void submit() {
        appraisals.submit(id, faculty);
    }

    // ---- reading and the form's own totals ----

    @Test
    void emptySectionReadsAsEmptyWithAZeroSummary() throws Exception {
        read(faculty, id, "teaching-courses")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records.length()").value(0))
                .andExpect(jsonPath("$.summary.courseCount").value(0))
                .andExpect(jsonPath("$.summary.averagePassPercentage").doesNotExist());
    }

    @Test
    void savingCoursesComputesTheTotalsTheFormAsksFor() throws Exception {
        save(faculty, id, "teaching-courses", List.of(
                course("CS101", 4, 90, 80.0, 60.0),
                course("CS102", 3.5, 80, 70.0, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records.length()").value(2))
                .andExpect(jsonPath("$.records[0].id").isNumber())
                .andExpect(jsonPath("$.summary.courseCount").value(2))
                .andExpect(jsonPath("$.summary.totalHoursPerWeek").value(7.5))
                .andExpect(jsonPath("$.summary.averagePassPercentage").value(85.0))
                .andExpect(jsonPath("$.summary.averagePhase1Feedback").value(75.0))
                .andExpect(jsonPath("$.summary.averagePhase2Feedback").value(60.0))
                .andExpect(jsonPath("$.summary.averageFeedback").value(70.0));
    }

    // ---- stable ids and minimal writes ----

    @Test
    void idsStayStableAndOnlyRealChangesAreWrittenAndAudited() throws Exception {
        save(faculty, id, "teaching-courses", List.of(course("A", 4, 90, null, null), course("B", 3, 80, null, null)))
                .andExpect(status().isOk());
        List<Map<String, Object>> first = savedRecords("teaching-courses");
        Object idA = first.get(0).get("id"), idB = first.get(1).get("id");
        assertEquals(1, auditCount("SECTION_SAVED"));

        // Re-sending exactly what is stored is a no-op: no write, no audit entry (autosave-friendly).
        save(faculty, id, "teaching-courses", first).andExpect(status().isOk());
        assertEquals(1, auditCount("SECTION_SAVED"));

        // Change one field of A, drop B, add C.
        List<Map<String, Object>> next = new ArrayList<>();
        Map<String, Object> a = new java.util.LinkedHashMap<>(first.get(0));
        a.put("passPercentage", 95);
        next.add(a);
        next.add(course("C", 2, 70, null, null));
        save(faculty, id, "teaching-courses", next).andExpect(status().isOk());

        List<Map<String, Object>> after = savedRecords("teaching-courses");
        assertEquals(2, after.size());
        assertEquals(idA, after.get(0).get("id"));                         // A kept its id
        assertEquals(95.0, ((Number) after.get(0).get("passPercentage")).doubleValue());
        assertEquals("C", after.get(1).get("courseCode"));                 // B gone, C new
        assertEquals(2, auditCount("SECTION_SAVED"));
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM teaching_courses WHERE id = ?")
                .param(((Number) idB).longValue()).query(Integer.class).single());
    }

    @Test
    void savingAnEmptyListClearsTheSection() throws Exception {
        save(faculty, id, "teaching-courses", List.of(course("A", 4, 90, null, null))).andExpect(status().isOk());
        save(faculty, id, "teaching-courses", List.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.records.length()").value(0));
        assertEquals(0, dbRows("teaching_courses"));
    }

    // ---- validation ----

    @Test
    void rejectedSaveChangesNothing() throws Exception {
        save(faculty, id, "teaching-courses", List.of(course("A", 4, 90, null, null))).andExpect(status().isOk());
        List<Map<String, Object>> stored = savedRecords("teaching-courses");

        List<Object> bad = new ArrayList<>(stored);
        bad.add(course("B", 3, 101, null, null)); // invalid second row
        bad.set(0, rec("id", stored.get(0).get("id"), "courseCode", "CHANGED"));
        save(faculty, id, "teaching-courses", bad).andExpect(status().isBadRequest());

        assertEquals(stored, savedRecords("teaching-courses"));
        assertEquals(1, auditCount("SECTION_SAVED"));
    }

    @Test
    void validationMessagesNameTheFieldInPlainLanguage() throws Exception {
        Map<String, Object> c = course("CS101", 4, 101, null, null);
        c.put("courseType", "SEMINAR");
        c.put("semester", 13);
        c.put("hoursPerWeek", 4.25);
        c.remove("courseName");
        c.put("surprise", "x");
        save(faculty, id, "teaching-courses", List.of(c))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Some values are invalid."))
                .andExpect(jsonPath("$.fieldErrors['records[0].passPercentage']")
                        .value("Pass percentage must be between 0 and 100."))
                .andExpect(jsonPath("$.fieldErrors['records[0].courseName']").value("Course name is required."))
                .andExpect(jsonPath("$.fieldErrors['records[0].courseType']")
                        .value("Course type must be one of: THEORY, LAB."))
                .andExpect(jsonPath("$.fieldErrors['records[0].semester']")
                        .value("Semester must be between 1 and 12."))
                .andExpect(jsonPath("$.fieldErrors['records[0].hoursPerWeek']")
                        .value("Hours per week can have at most 1 decimal places."))
                .andExpect(jsonPath("$.fieldErrors['records[0].surprise']").value("Not a recognised field."));
    }

    @Test
    void textRulesDatesAndRanges() throws Exception {
        save(faculty, id, "fdps", List.of(rec("title", "x".repeat(301), "mode", "ONLINE",
                "institutionVenue", "V\u0000", "startDate", "2026-03-10", "endDate", "2026-03-01", "days", 2)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors['records[0].title']")
                        .value("Title must be at most 300 characters."))
                .andExpect(jsonPath("$.fieldErrors['records[0].institutionVenue']")
                        .value("Organizing institution or venue contains characters that are not allowed."))
                .andExpect(jsonPath("$.fieldErrors['records[0].endDate']")
                        .value("End date must not be before start date."));

        save(faculty, id, "fdps", List.of(rec("title", "T", "mode", "ONLINE", "institutionVenue", "V",
                "startDate", "01/09/2026", "endDate", "2026-02-30", "days", 2)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors['records[0].startDate']")
                        .value("Start date must be a valid date (YYYY-MM-DD)."))
                .andExpect(jsonPath("$.fieldErrors['records[0].endDate']")
                        .value("End date must be a valid date (YYYY-MM-DD)."));

        save(faculty, id, "journal-publications", List.of(rec("title", "T", "authorPosition", "1", "journal", "J",
                "monthYear", "2026-13", "indexing", "SCOPUS")))
                .andExpect(jsonPath("$.fieldErrors['records[0].monthYear']")
                        .value("Month and year must be a month and year (YYYY-MM)."));
    }

    @Test
    void wrongTypesAreClientErrorsNeverServerErrors() throws Exception {
        Map<String, Object> c = course("A", 4, 90, null, null);
        c.put("semester", true);
        c.put("hoursPerWeek", "lots");
        c.put("courseCode", 12345);
        c.put("passPercentage", "1E999999999");
        save(faculty, id, "teaching-courses", List.of(c)).andExpect(status().isBadRequest());
        save(faculty, id, "teaching-courses", "not-a-list").andExpect(status().isBadRequest());
        mvc.perform(put("/api/appraisals/" + id + "/sections/teaching-courses").with(user(faculty)).with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.records").value("records is required."));
    }

    @Test
    void tooManyRecordsAreRejected() throws Exception {
        List<Object> many = new ArrayList<>();
        for (int i = 0; i < 201; i++) {
            many.add(rec("studentName", "S" + i, "rollNo", "R" + i, "achievement", "A", "level", "INST", "monthYear", "2026-01"));
        }
        save(faculty, id, "student-achievements", many).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.records").value("At most 200 records are allowed here."));
    }

    @Test
    void hostileTextIsStoredAsInertDataNotInterpreted() throws Exception {
        String evil = "Robert'); DROP TABLE users;-- <script>alert(1)</script>";
        save(faculty, id, "student-achievements", List.of(rec("studentName", evil, "rollNo", "R1",
                "achievement", "A", "level", "INST", "monthYear", "2026-01"))).andExpect(status().isOk());
        assertEquals(evil, savedRecords("student-achievements").get(0).get("studentName"));
        assertEquals(6, jdbc.sql("SELECT COUNT(*) FROM users").query(Integer.class).single()); // table intact
    }

    // ---- ownership of record ids ----

    @Test
    void anotherAppraisalsRecordIdCannotBeAdoptedOrOverwritten() throws Exception {
        save(faculty, id, "teaching-courses", List.of(course("MINE", 4, 90, null, null))).andExpect(status().isOk());
        Object myRecordId = savedRecords("teaching-courses").get(0).get("id");

        long theirs = appraisals.create(otherFaculty);
        save(otherFaculty, theirs, "teaching-courses",
                List.of(rec("id", myRecordId, "courseCode", "STOLEN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors['records[0].id']")
                        .value("This record does not exist in this appraisal."));

        assertEquals("MINE", savedRecords("teaching-courses").get(0).get("courseCode"));
    }

    @Test
    void duplicateIdsInOneRequestAreRejected() throws Exception {
        save(faculty, id, "teaching-courses", List.of(course("A", 4, 90, null, null))).andExpect(status().isOk());
        Object rid = savedRecords("teaching-courses").get(0).get("id");
        Map<String, Object> a = new java.util.LinkedHashMap<>(savedRecords("teaching-courses").get(0));
        save(faculty, id, "teaching-courses", List.of(a, a)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors['records[1].id']").value("This record appears more than once."));
        assertEquals(rid, savedRecords("teaching-courses").get(0).get("id"));
    }

    // ---- single-record and unique-per-platform sections ----

    @Test
    void singleRecordSectionsSaveReplaceAndClear() throws Exception {
        save(faculty, id, "mentoring-summary", List.of(rec("totalStudentsMentored", 25))).andExpect(status().isOk())
                .andExpect(jsonPath("$.singleton").value(true))
                .andExpect(jsonPath("$.records[0].totalStudentsMentored").value(25));
        save(faculty, id, "mentoring-summary", List.of(rec("totalStudentsMentored", 30))).andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].totalStudentsMentored").value(30));
        assertEquals(1, dbRows("student_mentoring"));
        save(faculty, id, "mentoring-summary", List.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.records.length()").value(0));
        assertEquals(0, dbRows("student_mentoring"));
    }

    @Test
    void singleRecordSectionRejectsMoreThanOneRecord() throws Exception {
        save(faculty, id, "mentoring-summary",
                List.of(rec("totalStudentsMentored", 1), rec("totalStudentsMentored", 2)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void phdProgressAllowsAnAllOptionalRecord() throws Exception {
        save(faculty, id, "phd-progress", List.of(rec("stage", "SYNOPSIS"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].stage").value("SYNOPSIS"))
                .andExpect(jsonPath("$.records[0].universityCenter").doesNotExist());
    }

    @Test
    void researchMetricsAllowOneRowPerPlatform() throws Exception {
        Map<String, Object> a = rec("platform", "SCOPUS", "totalPublications", 1, "totalCitations", 1, "hIndex", 1, "i10Index", 1);
        Map<String, Object> b = rec("platform", "SCOPUS", "totalPublications", 2, "totalCitations", 2, "hIndex", 2, "i10Index", 2);
        save(faculty, id, "research-metrics", List.of(a, b)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors['records[1].platform']")
                        .value("Platform is already used in another row."));
        b.put("platform", "GOOGLE_SCHOLAR");
        save(faculty, id, "research-metrics", List.of(a, b)).andExpect(status().isOk());
    }

    // ---- who may read and write ----

    @Test
    void sectionsAreLockedOnceSubmittedButStillReadable() throws Exception {
        save(faculty, id, "teaching-courses", List.of(course("A", 4, 90, null, null))).andExpect(status().isOk());
        submit();
        save(faculty, id, "teaching-courses", List.of()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This appraisal is locked while it is SUBMITTED."));
        read(faculty, id, "teaching-courses").andExpect(jsonPath("$.records.length()").value(1));
        assertEquals(1, dbRows("teaching_courses"));
    }

    @Test
    void reviewersReadButNeverWrite() throws Exception {
        save(faculty, id, "teaching-courses", List.of(course("A", 4, 90, null, null))).andExpect(status().isOk());
        read(hod, id, "teaching-courses").andExpect(status().isNotFound());      // draft is private
        submit();
        read(hod, id, "teaching-courses").andExpect(status().isOk()).andExpect(jsonPath("$.records.length()").value(1));
        save(hod, id, "teaching-courses", List.of()).andExpect(status().isForbidden());
        read(otherDeptHod, id, "teaching-courses").andExpect(status().isNotFound());
        save(otherDeptHod, id, "teaching-courses", List.of()).andExpect(status().isNotFound());
        read(principal, id, "teaching-courses").andExpect(status().isNotFound()); // not yet HoD-approved
        read(admin, id, "teaching-courses").andExpect(status().isNotFound());
        assertEquals(1, dbRows("teaching_courses"));
    }

    @Test
    void anotherFacultyCannotReadOrWriteMySections() throws Exception {
        read(otherFaculty, id, "teaching-courses").andExpect(status().isNotFound());
        save(otherFaculty, id, "teaching-courses", List.of(course("X", 1, 1, null, null)))
                .andExpect(status().isNotFound());
        assertEquals(0, dbRows("teaching_courses"));
    }

    @Test
    void unknownSectionIsNotFoundAndNoSqlCanBeSmuggledThroughTheKey() throws Exception {
        read(faculty, id, "nope").andExpect(status().isNotFound());
        read(faculty, id, "users").andExpect(status().isNotFound());
        save(faculty, id, "teaching_courses", List.of()).andExpect(status().isNotFound()); // table name, not a key
        // Semicolons are refused outright by the URL firewall (a client error, never reaching SQL).
        save(faculty, id, "teaching-courses;DROP", List.of()).andExpect(status().is4xxClientError());
    }

    @Test
    void savingWithoutACsrfTokenIsRejected() throws Exception {
        mvc.perform(put("/api/appraisals/" + id + "/sections/mentoring-summary").with(user(faculty))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"records\":[{\"totalStudentsMentored\":1}]}")).andExpect(status().isForbidden());
        assertEquals(0, dbRows("student_mentoring"));
    }

    @Test
    void sectionCountsFeedTheProgressSidebar() throws Exception {
        save(faculty, id, "teaching-courses", List.of(course("A", 4, 90, null, null), course("B", 3, 80, null, null)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/appraisals/" + id + "/sections").with(user(faculty)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.['teaching-courses']").value(2))
                .andExpect(jsonPath("$.['fdps']").value(0))
                .andExpect(jsonPath("$.length()").value(20));
    }

    @Test
    void savingATeachingSectionUpdatesTheAppraisalsLastSavedTime() throws Exception {
        jdbc.sql("UPDATE appraisals SET updated_at = '2020-01-01 00:00:00' WHERE id = ?").param(id).update();
        save(faculty, id, "teaching-courses", List.of(course("A", 4, 90, null, null))).andExpect(status().isOk());
        Integer year = jdbc.sql("SELECT YEAR(updated_at) FROM appraisals WHERE id = ?").param(id)
                .query(Integer.class).single();
        assertEquals(true, year >= 2026);
    }

    // ---- alignment with the official form (Part A, printed counts, outreach roles) ----

    @Test
    void partAStartsFromTheProfileAndCanBeCorrectedWhileDraft() throws Exception {
        FamsUserPrincipal fresh = db.faculty("f9@test.edu", "E009", "ME", "PROFESSOR");
        jdbc.sql("""
                UPDATE faculty_profiles SET contact_no = '9876543210', qualification = 'Ph.D.',
                  specialization = 'Thermal Engineering', phd_status = 'AWARDED',
                  joining_date_institution = '2010-06-01', teaching_experience_years = 15.5,
                  orcid = '0000-0001-2345-6789', scopus_id = '5711111111' WHERE user_id = ?""")
                .param(fresh.id()).update();
        long mine = appraisals.create(fresh);

        read(fresh, mine, "general-information").andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].contactNo").value("9876543210"))
                .andExpect(jsonPath("$.records[0].qualificationSpecialization").value("Ph.D., Thermal Engineering"))
                .andExpect(jsonPath("$.records[0].phdStatus").value("AWARDED"))
                .andExpect(jsonPath("$.records[0].joiningDateInstitution").value("2010-06-01"))
                .andExpect(jsonPath("$.records[0].teachingExperienceYears").value(15.5))
                .andExpect(jsonPath("$.records[0].researchIds").value("0000-0001-2345-6789 / 5711111111"));

        // Correcting it changes the appraisal's copy only; the profile (and other years) stay as they were.
        String idOfRecord = read(fresh, mine, "general-information").andReturn().getResponse().getContentAsString();
        Map<String, Object> rec = records(idOfRecord).get(0);
        rec.put("contactNo", "9000000000");
        save(fresh, mine, "general-information", List.of(rec)).andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].contactNo").value("9000000000"));
        assertEquals("9876543210", jdbc.sql("SELECT contact_no FROM faculty_profiles WHERE user_id = ?")
                .param(fresh.id()).query(String.class).single());
    }

    @Test
    void partAWithNoProfileDetailsStillGetsARowWithTheDefaultPhdStatus() throws Exception {
        read(faculty, id, "general-information").andExpect(status().isOk())
                .andExpect(jsonPath("$.singleton").value(true))
                .andExpect(jsonPath("$.records.length()").value(1))
                .andExpect(jsonPath("$.records[0].phdStatus").value("NOT_APPLICABLE"))
                .andExpect(jsonPath("$.records[0].contactNo").doesNotExist());
    }

    @Test
    void journalCountsFollowTheIndexingColumnsOfTheForm() throws Exception {
        List<Object> rows = new ArrayList<>();
        for (String idx : new String[] {"SCI_SCIE", "SCOPUS", "SCOPUS", "UGC_CARE_ABDC", "OTHERS", "OTHERS", "OTHERS"}) {
            rows.add(rec("title", "T" + rows.size(), "authorPosition", "1", "journal", "J",
                    "monthYear", "2026-05", "indexing", idx));
        }
        save(faculty, id, "journal-publications", rows).andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.sciScie").value(1))
                .andExpect(jsonPath("$.summary.scopus").value(2))
                .andExpect(jsonPath("$.summary.ugcCareAbdc").value(1))
                .andExpect(jsonPath("$.summary.others").value(3))
                .andExpect(jsonPath("$.summary.total").value(7));
    }

    @Test
    void fundedProjectTotalsMatchTheFormsFourFigures() throws Exception {
        save(faculty, id, "funded-projects", List.of(
                project("Edge AI", "RESEARCH", "SANCTIONED", 250000.50),
                project("IoT grid", "RESEARCH", "SANCTIONED", 100000),
                project("Smart farm", "RESEARCH", "APPLIED", 900000),       // applied: not in the sanctioned total
                project("Audit tool", "CONSULTANCY", "SANCTIONED", 40000),
                project("Cloud review", "CONSULTANCY", "APPLIED", 70000)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.sanctioned").value(3))
                .andExpect(jsonPath("$.summary.applied").value(2))
                .andExpect(jsonPath("$.summary.totalAmountSanctioned").value(350000.5))
                .andExpect(jsonPath("$.summary.consultancyRevenue").value(40000.0));
    }

    private static Map<String, Object> project(String title, String type, String status, double amount) {
        return rec("title", title, "role", "PI", "type", type, "fundingAgencyClient", "Agency", "amount", amount,
                "startDate", "2026-01-01", "endDate", "2026-12-31", "status", status, "year", 2026);
    }

    @Test
    void patentAndBookCountsMatchTheForm() throws Exception {
        List<Object> patents = new ArrayList<>();
        for (String st : new String[] {"FILED", "FILED", "PUBLISHED", "GRANTED"}) {
            patents.add(rec("applicantInventors", "A", "title", "P" + patents.size(), "type", "UTILITY",
                    "status", st, "recordDate", "2026-02-01"));
        }
        save(faculty, id, "patents-ipr", patents).andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.filed").value(2))
                .andExpect(jsonPath("$.summary.published").value(1))
                .andExpect(jsonPath("$.summary.granted").value(1));

        save(faculty, id, "books", List.of(
                rec("authors", "A", "title", "B1", "publisher", "P", "monthYear", "2026-01", "type", "BOOK"),
                rec("authors", "A", "title", "C1", "publisher", "P", "monthYear", "2026-02", "type", "CHAPTER"),
                rec("authors", "A", "title", "C2", "publisher", "P", "monthYear", "2026-03", "type", "CHAPTER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.books").value(1))
                .andExpect(jsonPath("$.summary.chapters").value(2));
    }

    @Test
    void outreachRoleMustBeOneOfTheFormsEleven() throws Exception {
        Map<String, Object> ok = rec("role", "JOURNAL_REVIEWER", "eventActivity", "Reviewed 3 papers",
                "organization", "IEEE", "eventDate", "2026-03-03");
        save(faculty, id, "outreach", List.of(ok)).andExpect(status().isOk());
        Map<String, Object> bad = new java.util.LinkedHashMap<>(ok);
        bad.put("role", "Keynote speaker");
        save(faculty, id, "outreach", List.of(bad)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors['records[0].role']").exists());
    }

    // ---- changes of 2026-10-09 ----

    @Test
    void theNumberOfDaysOfAnFdpIsWorkedOutFromItsDatesWhateverIsSent() throws Exception {
        save(faculty, id, "fdps", List.of(rec("title", "T", "mode", "ONLINE", "institutionVenue", "V",
                "startDate", "2026-01-30", "endDate", "2026-02-02", "days", 99)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.records[0].days").value(4));   // both end days count
        save(faculty, id, "fdps", List.of(rec("title", "T", "mode", "ONLINE", "institutionVenue", "V",
                "startDate", "2026-03-05", "endDate", "2026-03-05")))                              // no days sent at all
                .andExpect(status().isOk()).andExpect(jsonPath("$.records[0].days").value(1));
        save(faculty, id, "fdps", List.of(rec("title", "T", "mode", "ONLINE", "institutionVenue", "V",
                "startDate", "2026-01-01", "endDate", "2027-06-01")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors['records[0].endDate']").value("Number of days can be at most 366."));
    }

    @Test
    void atMostEightCoursesCanBeAdded() throws Exception {
        List<Object> eight = new ArrayList<>();
        for (int i = 0; i < 8; i++) eight.add(course("C" + i, 4, 90, null, null));
        save(faculty, id, "teaching-courses", eight).andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.courseCount").value(8));
        eight.add(course("C9", 4, 90, null, null));
        save(faculty, id, "teaching-courses", eight).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.records").value("At most 8 courses are allowed here."));
        assertEquals(8, dbRows("teaching_courses"));
    }

    @Test
    void theProgramIsChosenFromTheListAndTheBranchMustBelongToIt() throws Exception {
        Map<String, Object> pharmacy = course("P1", 4, 90, null, null);
        pharmacy.put("program", "PHARMACY");
        pharmacy.put("branch", "PHARMACOLOGY");
        save(faculty, id, "teaching-courses", List.of(pharmacy)).andExpect(status().isOk());

        pharmacy.put("branch", "CSE");   // not a branch of Pharmacy
        save(faculty, id, "teaching-courses", List.of(pharmacy)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors['records[0].branch']").value("Branch is not offered under the program you chose."));

        Map<String, Object> unknown = course("P2", 4, 90, null, null);
        unknown.put("program", "B.Tech");   // the old free text is no longer a program
        save(faculty, id, "teaching-courses", List.of(unknown)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors['records[0].program']").value("Program must be one of: B_TECH, DIPLOMA, MBA, PHARMACY."));
    }

    @Test
    void aRoleHasAFromDateAndAToDateAndTheyMustBeInOrder() throws Exception {
        save(faculty, id, "administrative-roles", List.of(rec("scope", "INSTITUTE", "role", "IQAC member",
                "fromDate", "2025-07-01", "toDate", "2026-05-31"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].fromDate").value("2025-07-01"));
        save(faculty, id, "administrative-roles", List.of(rec("scope", "INSTITUTE", "role", "IQAC member",
                "fromDate", "2026-05-31", "toDate", "2025-07-01"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors['records[0].toDate']").value("To date must not be before from date."));
        save(faculty, id, "administrative-roles", List.of(rec("scope", "INSTITUTE", "role", "IQAC member", "period", "2025-26")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors['records[0].fromDate']").value("From date is required."))
                .andExpect(jsonPath("$.fieldErrors['records[0].period']").value("Not a recognised field."));
    }

    @Test
    void theAuthorPositionIsANumberFromOneToEight() throws Exception {
        Map<String, Object> paper = rec("title", "T", "authorPosition", "8", "journal", "J", "monthYear", "2026-05", "indexing", "SCOPUS");
        save(faculty, id, "journal-publications", List.of(paper)).andExpect(status().isOk());
        paper.put("authorPosition", "9");
        save(faculty, id, "journal-publications", List.of(paper)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors['records[0].authorPosition']").value("Author position must be one of: 1, 2, 3, 4, 5, 6, 7, 8."));
    }

    @Test
    void aFundedProjectsYearMustBeOneOfTheAcademicYearsTwoYears() throws Exception {
        Map<String, Object> p = project("Edge AI", "RESEARCH", "SANCTIONED", 1000);   // year 2026
        save(faculty, id, "funded-projects", List.of(p)).andExpect(status().isOk());
        p.put("year", 2025);
        save(faculty, id, "funded-projects", List.of(p)).andExpect(status().isOk());
        p.put("year", 2024);
        save(faculty, id, "funded-projects", List.of(p)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors['records[0].year']")
                        .value("Year must be 2025 or 2026, the years of the academic year 2025-26."));
        p.put("year", 2027);
        save(faculty, id, "funded-projects", List.of(p)).andExpect(status().isBadRequest());
    }

    // ---- only what happened in the academic year counts (1 June to 31 May) ----

    @Test
    void everyDatedEntryMustFallInTheAcademicYearBeingAppraised() throws Exception {
        String within = " must be within the academic year 2025-26 (01-06-2025 to 31-05-2026).";
        // the first and last day are inside; the day before and the day after are not
        save(faculty, id, "outreach", List.of(
                rec("role", "RESOURCE_PERSON", "eventActivity", "A", "organization", "X", "eventDate", "2025-06-01"),
                rec("role", "RESOURCE_PERSON", "eventActivity", "B", "organization", "X", "eventDate", "2026-05-31")))
                .andExpect(status().isOk());
        save(faculty, id, "outreach", List.of(rec("role", "RESOURCE_PERSON", "eventActivity", "A", "organization", "X", "eventDate", "2025-05-31")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors['records[0].eventDate']").value("Date" + within));
        save(faculty, id, "outreach", List.of(rec("role", "RESOURCE_PERSON", "eventActivity", "A", "organization", "X", "eventDate", "2026-06-01")))
                .andExpect(status().isBadRequest());

        // months: June 2025 to May 2026
        String book = "authors";
        save(faculty, id, "books", List.of(rec(book, "A", "title", "T", "publisher", "P", "monthYear", "2025-05", "type", "BOOK")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors['records[0].monthYear']").value("Month and year" + within));
        save(faculty, id, "books", List.of(rec(book, "A", "title", "T", "publisher", "P", "monthYear", "2026-06", "type", "BOOK"))).andExpect(status().isBadRequest());
        save(faculty, id, "books", List.of(rec(book, "A", "title", "T", "publisher", "P", "monthYear", "2025-06", "type", "BOOK"),
                rec(book, "A", "title", "U", "publisher", "P", "monthYear", "2026-05", "type", "BOOK"))).andExpect(status().isOk());

        // a programme must both start and end inside the year
        save(faculty, id, "fdps", List.of(rec("title", "T", "mode", "ONLINE", "institutionVenue", "V", "startDate", "2026-05-30", "endDate", "2026-06-02")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors['records[0].endDate']").value("End date" + within));
        save(faculty, id, "fdps", List.of(rec("title", "T", "mode", "ONLINE", "institutionVenue", "V", "startDate", "2025-05-30", "endDate", "2025-06-02")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors['records[0].startDate']").value("Start date" + within));

        // a role's dates, a patent's date, a conference paper's month and a membership's year
        save(faculty, id, "administrative-roles", List.of(rec("scope", "INSTITUTE", "role", "R", "fromDate", "2024-07-01", "toDate", "2026-05-31")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors['records[0].fromDate']").value("From date" + within));
        save(faculty, id, "patents-ipr", List.of(rec("applicantInventors", "A", "title", "P", "type", "UTILITY", "status", "FILED", "recordDate", "2024-02-01")))
                .andExpect(status().isBadRequest());
        save(faculty, id, "conference-papers", List.of(rec("title", "T", "conference", "C", "level", "NAT", "monthYear", "2026-07", "citations", 0)))
                .andExpect(status().isBadRequest());
        save(faculty, id, "memberships-awards", List.of(rec("item", "I", "awardingBody", "B", "level", "NAT", "year", 2024)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors['records[0].year']").value("Year must be 2025 or 2026, the years of the academic year 2025-26."));
        save(faculty, id, "memberships-awards", List.of(rec("item", "I", "awardingBody", "B", "level", "NAT", "year", 2026))).andExpect(status().isOk());
    }

    @Test
    void thingsThatDescribeThePersonRatherThanTheYearAreNotHeldToIt() throws Exception {
        save(faculty, id, "research-metrics", List.of(rec("platform", "SCOPUS", "totalPublications", 3, "totalCitations", 4, "hIndex", 1, "i10Index", 0)))
                .andExpect(status().isOk());
        save(faculty, id, "phd-progress", List.of(rec("registrationYear", 2019))).andExpect(status().isOk());
        save(faculty, id, "general-information", List.of(rec("joiningDateInstitution", "2012-06-15", "phdStatus", "PURSUING"))).andExpect(status().isOk());
    }

    @Test
    void theAppraisalViewSaysWhichDaysTheAcademicYearSpans() throws Exception {
        mvc.perform(get("/api/appraisals/" + id).with(user(faculty)))
                .andExpect(jsonPath("$.academicYearStart").value("2025-06-01"))
                .andExpect(jsonPath("$.academicYearEnd").value("2026-05-31"));
    }
}
