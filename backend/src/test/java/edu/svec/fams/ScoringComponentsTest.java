package edu.svec.fams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.svec.fams.auth.Role;
import java.util.ArrayList;
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

/**
 * The cadre-wise scoring criteria of the college's "Cadre_wise" document (criteria B1 to B5): the maxima and the
 * components they are made of, as seeded, as shown on an appraisal, and as kept when the policy changes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ScoringComponentsTest {

    /** The component rows of version 1 (the college's document) once B5's are gone: four B1 parts for each cadre plus B2 to B4's. */
    static final int COMPONENT_ROWS = 74;

    /** The component rows of one policy version in the current form (V24): per cadre, four B1 parts, 4 for B2, 2 for B3 and 4 for B4. */
    static final int CURRENT_COMPONENT_ROWS = 70;

    /** The component rows of one policy version of V33: per cadre, three B1 parts and the same 4 + 2 + 4 for B2 to B4. */
    static final int V33_COMPONENT_ROWS = 65;

    /** B1 of the current policy (V33), by cadre: "student feedback" is gone and the parts are divided afresh. */
    static final Map<String, List<String>> B1_V33 = new LinkedHashMap<>();

    static {
        List<String> lecturerOrAsst = List.of("Workload & course delivery = 20", "course-file/assessment quality = 10",
                "innovative/remedial/advanced-learning practices = 10");
        B1_V33.put("LECTURER", lecturerOrAsst);
        B1_V33.put("ASST_PROF", lecturerOrAsst);
        B1_V33.put("SR_ASST_PROF", List.of("Workload & course delivery = 20", "course-file/assessment quality = 10",
                "innovative/remedial/advanced-learning practices = 5"));
        List<String> senior = List.of("Workload & course delivery = 15", "course-file/assessment quality = 10",
                "innovative/remedial/advanced-learning practices = 5");
        B1_V33.put("ASSOC_PROF", senior);
        B1_V33.put("PROFESSOR", senior);
    }

    /** The current B2, B3 and B4 (V24), the same for every cadre. */
    static final Map<String, List<String>> CURRENT = new LinkedHashMap<>();

    static {
        CURRENT.put("STUDENT_MENTORING", List.of("Mentoring = 6", "Project guidance = 4", "Student achievements = 3",
                "Academic / placement / competitive-exam support = 2"));
        CURRENT.put("FDP_CERTIFICATIONS", List.of("FDP / workshops / training = 5", "Certification = 10"));
        CURRENT.put("ADMINISTRATIVE", List.of("Department responsibilities = 4", "Institute roles = 3", "Curriculum / BoS = 3",
                "Accreditation works = 5"));
    }

    static final List<String> B1_TO_B4 = List.of("TEACHING_LEARNING", "STUDENT_MENTORING", "FDP_CERTIFICATIONS", "ADMINISTRATIVE");

    /** The maximum marks of the criteria that have one (B1 to B4), per cadre. B5 to B9 are marked per entry. */
    static final Map<String, int[]> SOURCE = new LinkedHashMap<>();

    static {
        SOURCE.put("LECTURER", new int[] {40, 15, 15, 15});
        SOURCE.put("ASST_PROF", new int[] {40, 12, 10, 15});
        SOURCE.put("SR_ASST_PROF", new int[] {35, 10, 8, 15});
        SOURCE.put("ASSOC_PROF", new int[] {30, 8, 5, 15});
        SOURCE.put("PROFESSOR", new int[] {30, 5, 5, 20});
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestDb db;
    @Autowired JdbcClient jdbc;

    TestHttp http;

    @BeforeEach
    void setUp() {
        db.reset();
        http = new TestHttp(mvc, json);
        db.user("admin@test.edu", Role.ADMIN);
    }

    private List<String> components(String cadre, String criterion) {
        return jdbc.sql("""
                SELECT CONCAT(pc.description, ' = ', pc.max_marks) FROM scoring_policy_components pc
                JOIN scoring_policies p ON p.id = pc.policy_id JOIN cadres c ON c.id = p.cadre_id
                WHERE c.code = ? AND p.version = 1 AND pc.criterion = ? ORDER BY pc.sort_order""")
                .params(cadre, criterion).query(String.class).list();
    }

    private JsonNode startAppraisal(String email, String employeeId, String cadre) throws Exception {
        db.faculty(email, employeeId, "CSE", cadre);
        MockHttpSession s = http.login(email);
        long id = http.read(http.post(s, "/api/appraisals", null).andExpect(status().isCreated())).get("id").asLong();
        return http.read(http.get(s, "/api/appraisals/" + id).andExpect(status().isOk()));
    }

    private static List<String> componentsOf(JsonNode score) {
        List<String> out = new ArrayList<>();
        score.get("components").forEach(c -> out.add(c.get("description").asText() + " = " + c.get("maxMarks").asInt()));
        return out;
    }

    // ---- as seeded ----

    @Test
    void everyCadreHasItsMaximaForB1ToB4AndNoMaximumForTheCriteriaMarkedPerEntry() {
        for (var e : SOURCE.entrySet()) {
            for (int c = 0; c < B1_TO_B4.size(); c++) {
                int max = jdbc.sql("""
                        SELECT k.max_marks FROM scoring_policy_criteria k JOIN scoring_policies p ON p.id = k.policy_id
                        JOIN cadres cd ON cd.id = p.cadre_id WHERE cd.code = ? AND p.version = 1 AND k.criterion = ?""")
                        .params(e.getKey(), B1_TO_B4.get(c)).query(Integer.class).single();
                assertEquals(e.getValue()[c], max, e.getKey() + " " + B1_TO_B4.get(c));
            }
            // B5 to B9 carry no maximum (stored as 0 and ignored)
            assertEquals(0, jdbc.sql("""
                    SELECT SUM(k.max_marks) FROM scoring_policy_criteria k JOIN scoring_policies p ON p.id = k.policy_id
                    JOIN cadres cd ON cd.id = p.cadre_id WHERE cd.code = ? AND p.version = 1 AND k.criterion NOT IN
                    ('TEACHING_LEARNING', 'STUDENT_MENTORING', 'FDP_CERTIFICATIONS', 'ADMINISTRATIVE')""").param(e.getKey()).query(Integer.class).single());
        }
    }

    @Test
    void everyComponentListAddsUpToItsCriterionsMaximum() {
        int mismatches = jdbc.sql("""
                SELECT COUNT(*) FROM (
                  SELECT k.policy_id, k.criterion FROM scoring_policy_criteria k
                  JOIN scoring_policy_components pc ON pc.policy_id = k.policy_id AND pc.criterion = k.criterion
                  GROUP BY k.policy_id, k.criterion, k.max_marks HAVING SUM(pc.max_marks) <> k.max_marks) x""").query(Integer.class).single();
        assertEquals(0, mismatches);
        // B1 to B4 of every cadre are broken down; the criteria marked per entry are not
        // (three versions of each cadre's policy: the document's, V24's and the current one of V33)
        assertEquals(60, jdbc.sql("SELECT COUNT(DISTINCT policy_id, criterion) FROM scoring_policy_components").query(Integer.class).single());
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM scoring_policy_components WHERE criterion NOT IN "
                + "('TEACHING_LEARNING','STUDENT_MENTORING','FDP_CERTIFICATIONS','ADMINISTRATIVE')").query(Integer.class).single());
        assertEquals(COMPONENT_ROWS + CURRENT_COMPONENT_ROWS + V33_COMPONENT_ROWS,
                jdbc.sql("SELECT COUNT(*) FROM scoring_policy_components").query(Integer.class).single());
    }

    // ---- the current policy (V24): B2, B3 and B4 are the same for every cadre ----

    private List<String> currentComponents(String cadre, String criterion) {
        return jdbc.sql("""
                SELECT CONCAT(pc.description, ' = ', pc.max_marks) FROM scoring_policy_components pc
                JOIN scoring_policies p ON p.id = pc.policy_id JOIN cadres c ON c.id = p.cadre_id
                WHERE c.code = ? AND p.version = 2 AND pc.criterion = ? ORDER BY pc.sort_order""")
                .params(cadre, criterion).query(String.class).list();
    }

    private List<String> v3Components(String cadre, String criterion) {
        return jdbc.sql("""
                SELECT CONCAT(pc.description, ' = ', pc.max_marks) FROM scoring_policy_components pc
                JOIN scoring_policies p ON p.id = pc.policy_id JOIN cadres c ON c.id = p.cadre_id
                WHERE c.code = ? AND p.version = 3 AND pc.criterion = ? ORDER BY pc.sort_order""")
                .params(cadre, criterion).query(String.class).list();
    }

    @Test
    void theCurrentPolicyHasNoStudentFeedbackAndTheNewB1PartsOfEveryCadre() {
        for (var e : SOURCE.entrySet()) {
            String cadre = e.getKey();
            assertEquals(B1_V33.get(cadre), v3Components(cadre, "TEACHING_LEARNING"), cadre);
            // the maximum of B1 is what it was, and the parts add up to it
            int max = jdbc.sql("""
                    SELECT k.max_marks FROM scoring_policy_criteria k JOIN scoring_policies p ON p.id = k.policy_id
                    JOIN cadres cd ON cd.id = p.cadre_id WHERE cd.code = ? AND p.version = 3 AND k.criterion = 'TEACHING_LEARNING'""")
                    .param(cadre).query(Integer.class).single();
            assertEquals(e.getValue()[0], max, cadre);
            assertEquals(max, jdbc.sql("""
                    SELECT SUM(pc.max_marks) FROM scoring_policy_components pc JOIN scoring_policies p ON p.id = pc.policy_id
                    JOIN cadres cd ON cd.id = p.cadre_id WHERE cd.code = ? AND p.version = 3 AND pc.criterion = 'TEACHING_LEARNING'""")
                    .param(cadre).query(Integer.class).single(), cadre + " parts add up");
            // B2 to B4 and the other maxima are carried over exactly from version 2
            for (String criterion : B1_TO_B4.subList(1, 4)) {
                assertEquals(currentComponents(cadre, criterion), v3Components(cadre, criterion), cadre + " " + criterion);
            }
            assertEquals(0, jdbc.sql("""
                    SELECT COUNT(*) FROM scoring_policy_criteria a JOIN scoring_policies pa ON pa.id = a.policy_id AND pa.version = 3
                    JOIN scoring_policies pb ON pb.academic_year_id = pa.academic_year_id AND pb.cadre_id = pa.cadre_id AND pb.version = 2
                    JOIN scoring_policy_criteria b ON b.policy_id = pb.id AND b.criterion = a.criterion
                    WHERE a.max_marks <> b.max_marks""").query(Integer.class).single());
        }
        // no student-feedback component in the current version; versions 1 and 2 keep it, so appraisals started under them are unchanged
        assertEquals(0, jdbc.sql("""
                SELECT COUNT(*) FROM scoring_policy_components pc JOIN scoring_policies p ON p.id = pc.policy_id
                WHERE p.version = 3 AND pc.description LIKE '%feedback%'""").query(Integer.class).single());
        assertEquals(10, jdbc.sql("""
                SELECT COUNT(*) FROM scoring_policy_components pc JOIN scoring_policies p ON p.id = pc.policy_id
                WHERE p.version IN (1, 2) AND pc.description = 'student feedback'""").query(Integer.class).single());
    }

    @Test
    void theCurrentPolicyGivesEveryCadreTheSameB2B3AndB4AndKeepsB1() {
        for (var e : SOURCE.entrySet()) {
            String cadre = e.getKey();
            for (var c : CURRENT.entrySet()) {
                assertEquals(c.getValue(), currentComponents(cadre, c.getKey()), cadre + " " + c.getKey());
                assertEquals(15, jdbc.sql("""
                        SELECT k.max_marks FROM scoring_policy_criteria k JOIN scoring_policies p ON p.id = k.policy_id
                        JOIN cadres cd ON cd.id = p.cadre_id WHERE cd.code = ? AND p.version = 2 AND k.criterion = ?""")
                        .params(cadre, c.getKey()).query(Integer.class).single(), cadre + " " + c.getKey() + " maximum");
            }
            // B1 is carried over as it was, maximum and parts
            assertEquals(e.getValue()[0], jdbc.sql("""
                    SELECT k.max_marks FROM scoring_policy_criteria k JOIN scoring_policies p ON p.id = k.policy_id
                    JOIN cadres cd ON cd.id = p.cadre_id WHERE cd.code = ? AND p.version = 2 AND k.criterion = 'TEACHING_LEARNING'""")
                    .param(cadre).query(Integer.class).single());
            assertEquals(components(cadre, "TEACHING_LEARNING"), currentComponents(cadre, "TEACHING_LEARNING"));
            // B5 to B9 still carry no maximum
            assertEquals(0, jdbc.sql("""
                    SELECT SUM(k.max_marks) FROM scoring_policy_criteria k JOIN scoring_policies p ON p.id = k.policy_id
                    JOIN cadres cd ON cd.id = p.cadre_id WHERE cd.code = ? AND p.version = 2 AND k.criterion NOT IN
                    ('TEACHING_LEARNING', 'STUDENT_MENTORING', 'FDP_CERTIFICATIONS', 'ADMINISTRATIVE')""").param(cadre).query(Integer.class).single());
            // nine criteria, as before
            assertEquals(9, jdbc.sql("""
                    SELECT COUNT(*) FROM scoring_policy_criteria k JOIN scoring_policies p ON p.id = k.policy_id
                    JOIN cadres cd ON cd.id = p.cadre_id WHERE cd.code = ? AND p.version = 2""").param(cadre).query(Integer.class).single());
        }
        // the removed components are not in the current policy at all
        assertEquals(0, jdbc.sql("""
                SELECT COUNT(*) FROM scoring_policy_components pc JOIN scoring_policies p ON p.id = pc.policy_id
                WHERE p.version = 2 AND (pc.description LIKE '%quality/accreditation%' OR pc.description LIKE '%measurable%')""")
                .query(Integer.class).single());
    }

    @Test
    void theOlderPolicyVersionIsLeftAsItWas() {
        // the document's figures and words, including the components that the current policy no longer has
        assertEquals(List.of("Mentoring = 4", "project guidance = 3", "student achievements = 3", "academic/placement/competitive-exam support = 2"),
                components("ASST_PROF", "STUDENT_MENTORING"));
        assertEquals(List.of("Department responsibilities = 4", "institute roles = 3", "curriculum/BoS = 3", "quality/accreditation = 3",
                "measurable institutional contribution = 2"), components("ASST_PROF", "ADMINISTRATIVE"));
        assertEquals(3, jdbc.sql("SELECT COUNT(*) FROM scoring_policies WHERE cadre_id = (SELECT id FROM cadres WHERE code = 'PROFESSOR')")
                .query(Integer.class).single());
    }

    @Test
    void everyCadresNewAppraisalShowsTheCurrentComponentsAndTotals() throws Exception {
        int n = 0;
        for (String cadre : SOURCE.keySet()) {
            JsonNode sheet = startAppraisal("c" + n + "@test.edu", "EC" + n, cadre).get("scores");
            n++;
            int b1 = SOURCE.get(cadre)[0];
            assertEquals(b1, sheet.get(0).get("maxMarks").asInt());
            int b1ToB4 = 0;
            for (int c = 1; c <= 3; c++) {
                JsonNode row = sheet.get(c);
                assertEquals(15, row.get("maxMarks").asInt(), cadre + " " + row.get("label").asText());
                assertEquals(CURRENT.get(row.get("criterion").asText()), componentsOf(row), cadre + " " + row.get("label").asText());
                b1ToB4 += row.get("maxMarks").asInt();
            }
            assertEquals(45, b1ToB4, cadre + ": B2 to B4 total");
        }
    }

    @Test
    void theComponentsAreTheDocumentsOwnWordsAndDifferByCadre() {
        assertEquals(List.of("Workload & course delivery = 20", "student feedback = 8",
                "course-file/assessment quality = 6", "innovative/remedial/advanced-learning practices = 6"), components("LECTURER", "TEACHING_LEARNING"));
        assertEquals(List.of("Workload & course delivery = 20", "student feedback = 8", "course-file/assessment quality = 6",
                "innovative/remedial/advanced-learning practices = 6"), components("ASST_PROF", "TEACHING_LEARNING"));
        assertEquals(List.of("Workload & course delivery = 20", "student feedback = 6", "course-file/assessment quality = 5",
                "innovative/remedial/advanced-learning practices = 4"), components("SR_ASST_PROF", "TEACHING_LEARNING"));
        assertEquals(List.of("Workload & course delivery = 20", "student feedback = 4", "course-file/assessment quality = 3",
                "innovative/remedial/advanced-learning practices = 3"), components("PROFESSOR", "TEACHING_LEARNING"));
        assertEquals(List.of("FDP/workshops/training = 5", "certification = 3"), components("SR_ASST_PROF", "FDP_CERTIFICATIONS"));
        assertEquals(List.of("Academic/administrative leadership = 4", "curriculum/BoS = 3", "quality/accreditation = 4",
                "committee/institutional leadership = 2", "measurable improvement = 2"), components("ASSOC_PROF", "ADMINISTRATIVE"));
        // the Professor's mentoring criterion has three components where the other cadres have four
        assertEquals(List.of("Academic/research mentoring = 2", "student/project/PG guidance = 1", "significant student achievements = 2"),
                components("PROFESSOR", "STUDENT_MENTORING"));
        // research and publications is marked per entry, so it has no components
        assertEquals(List.of(), components("PROFESSOR", "RESEARCH_PUBLICATIONS"));
    }

    // ---- on an appraisal ----

    @Test
    void anAppraisalShowsTheCriteriaAndComponentsOfItsOwnCadre() throws Exception {
        JsonNode lecturer = startAppraisal("lec@test.edu", "E1", "LECTURER").get("scores");
        JsonNode professor = startAppraisal("prof@test.edu", "E2", "PROFESSOR").get("scores");
        assertEquals(9, lecturer.size());

        assertEquals("B1", lecturer.get(0).get("reference").asText());
        assertEquals(40, lecturer.get(0).get("maxMarks").asInt());
        assertEquals(B1_V33.get("LECTURER"), componentsOf(lecturer.get(0)));
        assertEquals(30, professor.get(0).get("maxMarks").asInt());
        assertEquals(B1_V33.get("PROFESSOR"), componentsOf(professor.get(0)));
        // B2 has the same four parts for every cadre now
        assertEquals(4, professor.get(1).get("components").size());
        assertEquals(4, lecturer.get(1).get("components").size());

        for (JsonNode sheet : List.of(lecturer, professor)) {
            for (int c = 0; c < 9; c++) {
                JsonNode row = sheet.get(c);
                assertEquals("B" + (c + 1), row.get("reference").asText());
                int sum = 0;
                for (JsonNode part : row.get("components")) sum += part.get("maxMarks").asInt();
                if (c < 4) {
                    assertEquals(row.get("maxMarks").asInt(), sum, row.get("label").asText() + ": components add up to the maximum");
                } else {
                    assertEquals(0, row.get("components").size(), row.get("label").asText() + " has no breakdown in the document");
                }
            }
        }
    }

    // ---- when the policy changes ----

    @Test
    void aNewPolicyVersionKeepsABreakdownOnlyWhereTheMaximumIsUnchanged() throws Exception {
        JsonNode before = startAppraisal("old@test.edu", "E1", "ASST_PROF");
        long year = jdbc.sql("SELECT id FROM academic_years WHERE name = '2025-26'").query(Long.class).single();
        long cadre = jdbc.sql("SELECT id FROM cadres WHERE code = 'ASST_PROF'").query(Long.class).single();
        Map<String, Object> marks = new LinkedHashMap<>();
        int[] changed = {35, 15, 15, 15, 0, 0, 0, 0, 0};          // teaching 40 -> 35; the others as they are in the current policy
        String[] codes = {"TEACHING_LEARNING", "STUDENT_MENTORING", "FDP_CERTIFICATIONS", "ADMINISTRATIVE", "RESEARCH_PUBLICATIONS",
                "FUNDED_PROJECTS", "PATENTS_BOOKS_IPR", "OUTREACH", "MEMBERSHIPS_AWARDS"};
        for (int i = 0; i < codes.length; i++) marks.put(codes[i], changed[i]);

        JsonNode v2 = http.read(http.post(http.login("admin@test.edu"), "/api/admin/policies",
                Map.of("academicYearId", year, "cadreId", cadre, "marks", marks)).andExpect(status().isCreated()));
        assertEquals(4, v2.get("version").asInt());          // after the document's version 1, V24's version 2 and the current version 3
        // unchanged maxima keep their components; a changed maximum is published without a breakdown that no longer adds up
        assertEquals(4, v2.get("components").get("STUDENT_MENTORING").size());
        assertEquals(4, v2.get("components").get("ADMINISTRATIVE").size());
        assertFalse(v2.get("components").has("TEACHING_LEARNING"));

        JsonNode after = startAppraisal("new@test.edu", "E2", "ASST_PROF");
        assertEquals(35, after.get("scores").get(0).get("maxMarks").asInt());
        assertEquals(0, after.get("scores").get(0).get("components").size());
        assertEquals(4, after.get("scores").get(1).get("components").size());
        // the appraisal started earlier keeps the maximum and the breakdown it began with
        JsonNode again = http.read(http.get(http.login("old@test.edu"), "/api/appraisals/" + before.get("id").asLong()));
        assertEquals(40, again.get("scores").get(0).get("maxMarks").asInt());
        assertEquals(componentsOf(before.get("scores").get(0)), componentsOf(again.get("scores").get(0)));
        assertEquals(3, again.get("scores").get(0).get("components").size());
    }

    @Test
    void aNewAcademicYearStartsWithTheSameComponents() throws Exception {
        JsonNode created = http.read(http.post(http.login("admin@test.edu"), "/api/admin/academic-years",
                Map.of("name", "2027-28", "startDate", "2027-06-01", "endDate", "2028-05-31")).andExpect(status().isCreated()));
        assertEquals(V33_COMPONENT_ROWS, jdbc.sql("SELECT COUNT(*) FROM scoring_policy_components pc JOIN scoring_policies p ON p.id = pc.policy_id "
                + "WHERE p.academic_year_id = ?").param(created.get("id").asLong()).query(Integer.class).single());
        JsonNode sheet = startAppraisal("f@test.edu", "E1", "PROFESSOR").get("scores");     // started in the new, latest year
        assertEquals(B1_V33.get("PROFESSOR"), componentsOf(sheet.get(0)));
    }
}
