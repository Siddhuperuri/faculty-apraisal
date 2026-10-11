package edu.svec.fams.section;

import static edu.svec.fams.section.FieldSpec.choice;
import static edu.svec.fams.section.FieldSpec.date;
import static edu.svec.fams.section.FieldSpec.decimal;
import static edu.svec.fams.section.FieldSpec.integer;
import static edu.svec.fams.section.FieldSpec.listed;
import static edu.svec.fams.section.FieldSpec.monthYear;
import static edu.svec.fams.section.FieldSpec.text;
import static edu.svec.fams.section.FieldSpec.year;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Every repeatable/single form section of Parts A and B, in the official order. To add a field, change a limit or
 * add a section, edit it here (and add the matching column in a migration); no other code changes.
 * Required means the column is NOT NULL in the database. Allowed values mirror the CHECK constraints.
 */
public final class Sections {
    private Sections() {}

    private static final Map<String, SectionSpec> ALL = new LinkedHashMap<>();

    /** A faculty member teaches 4 courses in each of the year's 2 semesters; no more can be added. */
    public static final int MAX_COURSES = 8;

    /** A faculty member can record at most 2 funded projects or consultancies in an academic year (and they earn at most 10 marks). */
    public static final int MAX_FUNDED_PROJECTS = 2;

    private static void add(SectionSpec s) { ALL.put(s.key(), s); }

    static {
        // Part A - General information (name, employee ID, department, designation, e-mail and academic year
        // are read-only: they come from the faculty profile and the appraisal itself)
        add(SectionSpec.single("general-information", "general_information",
                text("contactNo", "contact_no", "Contact number", 20, false).forSubmission(),
                text("qualificationSpecialization", "qualification_specialization",
                        "Highest qualification and specialization", 300, false).forSubmission(),
                listed("phdStatus", "phd_status", "Ph.D. status", true, "PHD_STATUS"),
                date("joiningDateInstitution", "joining_date_institution", "Date of joining (institution)", false).forSubmission(),
                date("joiningDateDesignation", "joining_date_designation", "Date of promotion", false),
                decimal("teachingExperienceYears", "teaching_experience_years", "Teaching experience (years)", 0, 80, 1, false),
                decimal("industryExperienceYears", "industry_experience_years", "Industry experience (years)", 0, 80, 1, false),
                decimal("researchExperienceYears", "research_experience_years", "Research experience (years)", 0, 80, 1, false),
                text("researchIds", "research_ids", "ORCID / Scopus / Google Scholar / Vidwan ID", 300, false)));

        // Part B, 1. Teaching & Learning
        add(SectionSpec.list("teaching-courses", "teaching_courses",
                text("courseCode", "course_code", "Course code", 32, true),
                listed("courseRole", "course_role", "Role in the course", true, "COURSE_ROLE"),
                text("courseName", "course_name", "Course name", 160, true),
                listed("courseType", "course_type", "Course type", true, "COURSE_TYPE"),
                listed("program", "program", "Program", true, "PROGRAM"),
                listed("branch", "branch", "Branch", true, "BRANCH"),
                integer("semester", "semester", "Semester", 1, 12, true).fromList("SEMESTER"),
                listed("section", "class_section", "Section", true, "SECTION"),
                decimal("hoursPerWeek", "hours_per_week", "Hours per week", 1, 6, 0, true).fromList("HOURS_PER_WEEK"),
                decimal("passPercentage", "pass_percentage", "Pass percentage", 0, 100, 2, true),
                decimal("phase1Feedback", "phase1_feedback", "Phase-1 feedback percentage", 0, 100, 2, false),
                decimal("phase2Feedback", "phase2_feedback", "Phase-2 feedback percentage", 0, 100, 2, false))
                .dependentChoice("branch", "program", "BRANCH")
                .maxRecords(MAX_COURSES, "courses")
                .summary(TeachingSummary::of));

        // Teaching & Learning: institutional roles held, each earning the marks the scoring policy sets for it
        add(SectionSpec.list("institutional-roles", "institutional_roles_held",
                listed("role", "role_code", "Institutional role", true, "INSTITUTIONAL_ROLE"),
                text("remarks", "remarks", "Remarks", 300, false))
                .uniqueBy("role")
                .maxRecords(10, "institutional roles"));

        // 2. Student mentoring, project guidance & achievements
        add(SectionSpec.single("mentoring-summary", "student_mentoring",
                integer("totalStudentsMentored", "total_students_mentored", "Total students mentored", 0, 50, true)));
        add(SectionSpec.list("student-achievements", "student_achievements",
                text("studentName", "student_name", "Student name", 120, true),
                text("rollNo", "roll_no", "Roll number", 32, true),
                text("achievement", "achievement", "Achievement", 300, true),
                listed("level", "level", "Level", true, "ACHIEVEMENT_LEVEL"),
                monthYear("monthYear", "month_year", "Month and year", true)));
        add(SectionSpec.list("student-projects", "student_projects",
                listed("level", "level", "Level", true, "PROJECT_LEVEL"),
                text("title", "title", "Project title", 300, true),
                integer("studentCount", "student_count", "Number of students", 0, 1000, true),
                listed("outcome", "outcome", "Outcome", true, "PROJECT_OUTCOME")));

        // 3. FDPs / Certifications
        add(SectionSpec.list("fdps", "fdps",
                text("title", "title", "Title", 300, true),
                listed("mode", "mode", "Mode", true, "FDP_MODE"),
                text("institutionVenue", "institution_venue", "Organizing institution or venue", 200, true),
                integer("days", "days", "Duration (in days)", 1, 366, true)));
        add(SectionSpec.list("certifications", "certifications",
                listed("platform", "platform", "Platform", true, "CERT_PLATFORM"),
                text("platformOther", "platform_other", "Name of the platform", 100, false),
                text("title", "title", "Course title", 300, true),
                integer("durationWeeks", "duration_weeks", "Duration (in weeks)", 1, 520, true),
                text("gradeScore", "grade_score", "Grade or score", 40, false))
                .conditionalField("platformOther", "platform", "OTHER"));

        // 4. Administrative responsibilities
        add(SectionSpec.list("administrative-roles", "administrative_roles",
                choice("scope", "scope", "Level", true, "INSTITUTE", "DEPARTMENT"),
                text("role", "role", "Role", 160, true),
                text("description", "description", "Responsibility or description", 1000, false),
                date("fromDate", "from_date", "From date", true),
                date("toDate", "to_date", "To date", true))
                .dateRange("fromDate", "toDate")
                .suggestions("role", "scope", Map.of("INSTITUTE", "INSTITUTE_ROLE_SUGGESTION", "DEPARTMENT", "DEPARTMENT_ROLE_SUGGESTION")));
        add(SectionSpec.list("events", "events",
                text("activityType", "activity_type", "Type of activity", 80, true),
                text("role", "role", "Role", 80, true),
                text("title", "title", "Title or details of the event", 300, true),
                date("startDate", "start_date", "Start date", true),
                date("endDate", "end_date", "End date", true),
                integer("beneficiaries", "beneficiaries", "Number of beneficiaries", 0, 100000, true))
                .dateRange("startDate", "endDate"));

        // 5. Research & publications
        add(SectionSpec.list("journal-publications", "journal_publications",
                text("title", "title", "Title of paper", 400, true),
                listed("authorPosition", "author_position", "Author position", true, "AUTHOR_POSITION"),
                text("journal", "journal", "Journal", 200, true),
                text("volumeIssuePage", "volume_issue_page", "Volume, issue and page numbers", 80, false),
                monthYear("monthYear", "month_year", "Month and year", true),
                listed("indexing", "indexing", "Indexing", true, "JOURNAL_INDEXING"),
                text("doiIssn", "doi_issn", "DOI or ISSN", 80, false))
                .summary(Summaries::journals));
        add(SectionSpec.list("conference-papers", "conference_papers",
                text("title", "title", "Title", 400, true),
                text("conference", "conference", "Conference", 200, true),
                listed("level", "level", "Level", true, "CONFERENCE_LEVEL"),
                monthYear("monthYear", "month_year", "Month and year", true),
                text("venue", "venue", "Venue", 200, false),
                text("doiIndexedIn", "doi_indexed_in", "DOI or indexed in", 120, false),
                integer("citations", "citations", "Number of citations", 0, 1000000, true)));
        add(SectionSpec.list("research-metrics", "research_metrics",
                listed("platform", "platform", "Platform", true, "METRICS_PLATFORM"),
                integer("totalPublications", "total_publications", "Total publications", 0, 1000000, true),
                integer("totalCitations", "total_citations", "Total citations", 0, 10000000, true),
                integer("hIndex", "h_index", "h-index", 0, 1000, true),
                integer("i10Index", "i10_index", "i10-index", 0, 100000, true))
                .uniqueBy("platform"));
        add(SectionSpec.list("research-scholars", "research_scholars",
                text("name", "name", "Scholar name", 120, true),
                listed("degree", "degree", "Degree", true, "SCHOLAR_DEGREE"),
                text("universityRegNo", "university_reg_no", "University and registration number", 120, false),
                listed("status", "status", "Status", true, "SCHOLAR_STATUS"),
                year("year", "year", "Year", true)));
        add(SectionSpec.single("phd-progress", "phd_progress",
                text("universityCenter", "university_center", "University or centre", 200, false),
                year("registrationYear", "registration_year", "Year of registration", false),
                listed("stage", "stage", "Current stage", false, "PHD_STAGE"),
                text("progress", "progress", "Progress during the year", 1000, false)));

        // 6. Funded projects / consultancy
        add(SectionSpec.list("funded-projects", "funded_projects",
                text("title", "title", "Title", 300, true),
                listed("role", "role", "Role", true, "FUNDED_ROLE"),
                text("team", "team", "Team", 300, false),
                listed("type", "type", "Type", true, "FUNDED_TYPE"),
                text("fundingAgencyClient", "funding_agency_client", "Funding agency or client", 200, true),
                decimal("amount", "amount", "Amount (Rs.)", 0, 1e11, 2, true),
                date("startDate", "start_date", "Start date", true),
                date("endDate", "end_date", "End date", true),
                listed("status", "status", "Status", true, "FUNDED_STATUS"),
                year("year", "year", "Year", true))
                .dateRange("startDate", "endDate")
                .maxRecords(MAX_FUNDED_PROJECTS, "funded projects")
                .summary(Summaries::fundedProjects));

        // 7. Patents, books & IPR
        add(SectionSpec.list("patents-ipr", "patents_ipr",
                text("applicantInventors", "applicant_inventors", "Applicant or inventors", 300, true),
                text("title", "title", "Title", 300, true),
                text("applicationPatentNo", "application_patent_no", "Application or patent number", 80, false),
                listed("type", "type", "Type", true, "PATENT_TYPE"),
                listed("status", "status", "Status", true, "PATENT_STATUS"),
                date("recordDate", "record_date", "Date", true))
                .summary(Summaries::patents));
        add(SectionSpec.list("books", "books",
                text("authors", "authors", "Authors", 300, true),
                text("title", "title", "Title of book or chapter", 300, true),
                text("publisher", "publisher", "Publisher", 160, true),
                FieldSpec.isbn("isbn", "isbn", "ISBN", true),
                monthYear("monthYear", "month_year", "Month and year", true),
                listed("type", "type", "Type", true, "BOOK_TYPE"))
                .summary(Summaries::books));

        // 8. Outreach
        add(SectionSpec.list("outreach", "outreach",
                listed("role", "role", "Role", true, "OUTREACH_ROLE"),
                text("eventActivity", "event_activity", "Event or activity", 300, true),
                text("organization", "organization", "Name of organization or institution", 200, true),
                text("venue", "venue", "Venue", 200, false),
                date("eventDate", "event_date", "Date", true)));

        // 9. Professional memberships, awards & recognitions
        add(SectionSpec.list("memberships-awards", "memberships_awards",
                text("item", "item", "Membership, award or recognition", 300, true),
                text("awardingBody", "awarding_body", "Professional or awarding body", 200, true),
                listed("level", "level", "Level", true, "ACHIEVEMENT_LEVEL"),
                year("year", "year", "Year", true))
                .suggestions("awardingBody", "", Map.of("", "AWARDING_BODY_SUGGESTION")));

        // 10. Any other contributions
        add(SectionSpec.single("other-contributions", "other_contributions",
                text("departmentContribution", "department_contribution", "Contribution at department level", 5000, false),
                text("instituteContribution", "institute_contribution", "Contribution at institute level", 5000, false)));
    }

    /**
     * Only what was achieved, done or received in the academic year being appraised (1 June to 31 May) is considered, so the
     * entries below must be dated inside it. A field not listed here (joining dates, the year a Ph.D. was registered)
     * describes the person, not something done in the year.
     */
    private static void inAcademicYear(String section, String... fields) { ALL.get(section).inAcademicYear(fields); }

    static {
        inAcademicYear("student-achievements", "monthYear");
        inAcademicYear("administrative-roles", "fromDate", "toDate");
        inAcademicYear("events", "startDate", "endDate");
        inAcademicYear("journal-publications", "monthYear");
        inAcademicYear("conference-papers", "monthYear");
        inAcademicYear("research-scholars", "year");
        inAcademicYear("funded-projects", "year");
        inAcademicYear("patents-ipr", "recordDate");
        inAcademicYear("books", "monthYear");
        inAcademicYear("outreach", "eventDate");
        inAcademicYear("memberships-awards", "year");
    }

    public static Map<String, SectionSpec> all() { return Collections.unmodifiableMap(ALL); }

    public static SectionSpec get(String key) { return ALL.get(key); }
}
