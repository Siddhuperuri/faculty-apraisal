package edu.svec.fams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.svec.fams.appraisal.AppraisalService;
import edu.svec.fams.auth.FamsUserPrincipal;
import edu.svec.fams.section.FieldSpec;
import edu.svec.fams.section.SectionService;
import edu.svec.fams.section.SectionSpec;
import edu.svec.fams.section.Sections;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/**
 * The section definitions in {@link Sections} are plain strings, so these tests prove they match the real
 * database: every column, every allowed value, and a full save/read round trip for every section.
 */
@SpringBootTest
@ActiveProfiles("test")
class SectionSchemaTest {

    @Autowired TestDb db;
    @Autowired JdbcClient jdbc;
    @Autowired AppraisalService appraisals;
    @Autowired SectionService sections;

    FamsUserPrincipal faculty;
    long appraisalId;

    @BeforeEach
    void setUp() {
        db.reset();
        faculty = db.faculty("f1@test.edu", "E001", "CSE", "ASST_PROF");
        appraisalId = appraisals.create(faculty);
    }

    /** One valid record per section. Shared with the behaviour tests. */
    static Map<String, Map<String, Object>> samples() {
        Map<String, Map<String, Object>> m = new LinkedHashMap<>();
        m.put("general-information", rec("contactNo", "9876543210", "qualificationSpecialization", "M.Tech, Software Engineering",
                "phdStatus", "PURSUING", "joiningDateInstitution", "2018-06-15", "joiningDateDesignation", "2022-07-01",
                "teachingExperienceYears", 8.5, "industryExperienceYears", 1, "researchExperienceYears", 3.5,
                "researchIds", "ORCID 0000-0001-2345-6789"));
        m.put("teaching-courses", rec("courseCode", "CS101", "courseName", "Intro to Programming",
                "courseType", "THEORY", "program", "B.Tech", "branch", "CSE", "semester", 3, "sections", 2,
                "hoursPerWeek", 4, "passPercentage", 92.5, "phase1Feedback", 80, "phase2Feedback", 85));
        m.put("mentoring-summary", rec("totalStudentsMentored", 25));
        m.put("student-achievements", rec("studentName", "A. Student", "rollNo", "21A91A0501",
                "achievement", "Won hackathon", "level", "STATE", "monthYear", "2026-03"));
        m.put("student-projects", rec("level", "UG", "title", "Smart attendance", "studentCount", 4,
                "outcome", "PAPER"));
        m.put("fdps", rec("title", "AI for Educators", "mode", "ONLINE", "institutionVenue", "IIT Madras",
                "startDate", "2026-07-01", "endDate", "2026-07-05", "days", 5));
        m.put("certifications", rec("platform", "NPTEL", "title", "Data Structures", "startDate", "2026-01-10",
                "endDate", "2026-04-10", "durationWeeksHours", "12 weeks", "gradeScore", "Elite"));
        m.put("administrative-roles", rec("scope", "DEPARTMENT", "role", "Timetable Coordinator",
                "description", "Prepares the timetable", "period", "2026-27"));
        m.put("events", rec("activityType", "Guest lecture", "role", "Organizer", "title", "Cloud workshop",
                "startDate", "2026-08-01", "endDate", "2026-08-02", "beneficiaries", 120));
        m.put("journal-publications", rec("title", "A study of X", "authorPosition", "First", "journal", "J. of Y",
                "volumeIssuePage", "12(3):45-60", "monthYear", "2026-05", "indexing", "SCOPUS", "doiIssn", "10.1000/xyz"));
        m.put("conference-papers", rec("title", "Paper Z", "conference", "ICML", "level", "INTL",
                "monthYear", "2026-06", "venue", "Vienna", "doiIndexedIn", "IEEE Xplore", "citations", 2));
        m.put("research-metrics", rec("platform", "SCOPUS", "totalPublications", 10, "totalCitations", 100,
                "hIndex", 5, "i10Index", 3));
        m.put("research-scholars", rec("name", "R. Scholar", "degree", "PHD", "universityRegNo", "JNTU/123",
                "status", "REGISTERED", "year", 2025));
        m.put("phd-progress", rec("universityCenter", "JNTU Kakinada", "registrationYear", 2024,
                "stage", "COURSE_WORK", "progress", "Completed coursework"));
        m.put("funded-projects", rec("title", "Edge AI", "role", "PI", "team", "A, B", "type", "RESEARCH",
                "fundingAgencyClient", "DST", "amount", 250000.50, "startDate", "2026-01-01",
                "endDate", "2027-12-31", "status", "SANCTIONED", "year", 2026));
        m.put("patents-ipr", rec("applicantInventors", "A, B", "title", "Device for Q", "applicationPatentNo", "202641001234",
                "type", "UTILITY", "status", "FILED", "recordDate", "2026-02-15"));
        m.put("books", rec("authors", "A, B", "title", "Algorithms", "publisher", "Pearson", "isbn", "9780000000000",
                "monthYear", "2026-04", "type", "BOOK"));
        m.put("outreach", rec("role", "RESOURCE_PERSON", "eventActivity", "FDP on ML", "organization", "ABC College",
                "venue", "Hyderabad", "eventDate", "2026-09-09"));
        m.put("memberships-awards", rec("item", "IEEE Member", "awardingBody", "IEEE", "level", "INTL", "year", 2026));
        m.put("other-contributions", rec("departmentContribution", "Lab setup", "instituteContribution", "NAAC data"));
        return m;
    }

    static Map<String, Object> rec(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void everyTablePartOfPartBHasADefinition() {
        // The 20 Part A/B tables created in V3 (general_information and 19 Part B).
        assertEquals(20, Sections.all().size());
        assertEquals(Sections.all().keySet(), samples().keySet(), "every section needs a sample in this test");
    }

    @Test
    void definedColumnsMatchTheDatabaseExactly() {
        for (SectionSpec s : Sections.all().values()) {
            Set<String> dbColumns = new HashSet<>(jdbc.sql("""
                    SELECT column_name FROM information_schema.columns
                    WHERE table_schema = DATABASE() AND table_name = ?""")
                    .param(s.table()).query(String.class).list());
            dbColumns.remove("id");
            dbColumns.remove("appraisal_id");
            Set<String> specColumns = s.fields().stream().map(FieldSpec::column).collect(Collectors.toSet());
            assertEquals(dbColumns, specColumns, "columns of " + s.table());
        }
    }

    @Test
    void allowedValuesMatchTheDatabaseCheckConstraints() {
        // MySQL stores the clause as: in (_utf8mb4\'THEORY\',_utf8mb4\'LAB\') (backslash before each quote)
        Pattern literal = Pattern.compile("\\\\?'([A-Za-z0-9_]+)\\\\?'");
        for (SectionSpec s : Sections.all().values()) {
            List<String> clauses = jdbc.sql("""
                    SELECT cc.check_clause FROM information_schema.check_constraints cc
                    JOIN information_schema.table_constraints tc
                      ON tc.constraint_schema = cc.constraint_schema AND tc.constraint_name = cc.constraint_name
                    WHERE tc.table_schema = DATABASE() AND tc.table_name = ?""")
                    .param(s.table()).query(String.class).list();
            for (FieldSpec f : s.fields()) {
                if (f.type() != FieldSpec.Type.ENUM) continue;
                String clause = clauses.stream().filter(c -> c.contains("`" + f.column() + "` in (")).findFirst()
                        .orElseThrow(() -> new AssertionError("no CHECK for " + s.table() + "." + f.column()));
                Set<String> dbValues = new HashSet<>();
                Matcher m = literal.matcher(clause);
                while (m.find()) dbValues.add(m.group(1));
                assertEquals(dbValues, new HashSet<>(f.allowed()), s.table() + "." + f.column());
            }
        }
    }

    @Test
    void everySectionRoundTripsASampleRecord() {
        samples().forEach((key, sample) -> {
            SectionService.SectionView saved = sections.save(appraisalId, key, faculty, List.of(sample));
            assertEquals(1, saved.records().size(), key);
            Map<String, Object> got = sections.read(appraisalId, key, faculty).records().get(0);
            sample.forEach((field, expected) -> {
                Object actual = got.get(field);
                if (expected instanceof Number) {
                    assertEquals(0, new BigDecimal(expected.toString()).compareTo(new BigDecimal(actual.toString())),
                            key + "." + field + " expected " + expected + " got " + actual);
                } else {
                    assertEquals(expected, actual instanceof java.time.LocalDate d ? d.toString() : actual,
                            key + "." + field);
                }
            });
        });
    }

    @Test
    void everyAllowedValueOfEveryChoiceFieldIsAcceptedByTheDatabase() {
        samples().forEach((key, sample) -> {
            SectionSpec spec = Sections.get(key);
            for (FieldSpec f : spec.fields()) {
                if (f.type() != FieldSpec.Type.ENUM) continue;
                for (String value : f.allowed()) {
                    Map<String, Object> variant = new LinkedHashMap<>(sample);
                    variant.put(f.name(), value);
                    SectionService.SectionView v = sections.save(appraisalId, key, faculty, List.of(variant));
                    assertEquals(value, v.records().get(0).get(f.name()), key + "." + f.name());
                }
            }
        });
    }

    @Test
    void textLimitsNeverExceedTheColumnAndTheLimitItselfIsAccepted() {
        // A spec limit larger than the real column would turn a valid-looking request into a 500.
        for (SectionSpec spec : Sections.all().values()) {
            for (FieldSpec f : spec.fields()) {
                if (f.type() != FieldSpec.Type.TEXT) continue;
                int declared = jdbc.sql("""
                        SELECT character_maximum_length FROM information_schema.columns
                        WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?""")
                        .params(spec.table(), f.column()).query(Integer.class).single();
                assertTrue(f.maxLength() <= declared,
                        spec.table() + "." + f.column() + ": limit " + f.maxLength() + " > column " + declared);
                Map<String, Object> sample = new LinkedHashMap<>(samples().get(spec.key()));
                sample.put(f.name(), "x".repeat(f.maxLength()));
                sections.save(appraisalId, spec.key(), faculty, List.of(sample));
            }
        }
    }
}
