package edu.svec.fams.section;

import static edu.svec.fams.section.FieldSpec.choice;
import static edu.svec.fams.section.FieldSpec.date;
import static edu.svec.fams.section.FieldSpec.decimal;
import static edu.svec.fams.section.FieldSpec.integer;
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

    private static void add(SectionSpec s) { ALL.put(s.key(), s); }

    static {
        // Part A - General information (name, employee ID, department, designation, e-mail and academic year
        // are read-only: they come from the faculty profile and the appraisal itself)
        add(SectionSpec.single("general-information", "general_information",
                text("contactNo", "contact_no", "Contact number", 20, false).forSubmission(),
                text("qualificationSpecialization", "qualification_specialization",
                        "Highest qualification and specialization", 300, false).forSubmission(),
                choice("phdStatus", "phd_status", "Ph.D. status", true, "AWARDED", "PURSUING", "NOT_APPLICABLE"),
                date("joiningDateInstitution", "joining_date_institution", "Date of joining (institution)", false).forSubmission(),
                date("joiningDateDesignation", "joining_date_designation", "Date of joining (present designation)", false)
                        .forSubmission(),
                decimal("teachingExperienceYears", "teaching_experience_years", "Teaching experience (years)", 0, 80, 1, false),
                decimal("industryExperienceYears", "industry_experience_years", "Industry experience (years)", 0, 80, 1, false),
                decimal("researchExperienceYears", "research_experience_years", "Research experience (years)", 0, 80, 1, false),
                text("researchIds", "research_ids", "ORCID / Scopus / Google Scholar / Vidwan ID", 300, false)));

        // Part B, 1. Teaching & Learning
        add(SectionSpec.list("teaching-courses", "teaching_courses",
                text("courseCode", "course_code", "Course code", 32, true),
                text("courseName", "course_name", "Course name", 160, true),
                choice("courseType", "course_type", "Course type", true, "THEORY", "LAB"),
                text("program", "program", "Program", 40, true),
                text("branch", "branch", "Branch", 40, true),
                integer("semester", "semester", "Semester", 1, 12, true),
                integer("sections", "sections", "Number of sections", 0, 100, true),
                decimal("hoursPerWeek", "hours_per_week", "Hours per week", 0, 168, 1, true),
                decimal("passPercentage", "pass_percentage", "Pass percentage", 0, 100, 2, true),
                decimal("phase1Feedback", "phase1_feedback", "Phase-1 feedback percentage", 0, 100, 2, false),
                decimal("phase2Feedback", "phase2_feedback", "Phase-2 feedback percentage", 0, 100, 2, false))
                .summary(TeachingSummary::of));

        // 2. Student mentoring, project guidance & achievements
        add(SectionSpec.single("mentoring-summary", "student_mentoring",
                integer("totalStudentsMentored", "total_students_mentored", "Total students mentored", 0, 10000, true)));
        add(SectionSpec.list("student-achievements", "student_achievements",
                text("studentName", "student_name", "Student name", 120, true),
                text("rollNo", "roll_no", "Roll number", 32, true),
                text("achievement", "achievement", "Achievement", 300, true),
                choice("level", "level", "Level", true, "INST", "STATE", "NAT", "INTL"),
                monthYear("monthYear", "month_year", "Month and year", true)));
        add(SectionSpec.list("student-projects", "student_projects",
                choice("level", "level", "Level", true, "DIPLOMA", "UG", "PG"),
                text("title", "title", "Project title", 300, true),
                integer("studentCount", "student_count", "Number of students", 0, 1000, true),
                choice("outcome", "outcome", "Outcome", true, "PAPER", "PATENT", "PROTOTYPE", "COMPETITION")));

        // 3. FDPs / Certifications
        add(SectionSpec.list("fdps", "fdps",
                text("title", "title", "Title", 300, true),
                choice("mode", "mode", "Mode", true, "OFFLINE", "ONLINE", "BLENDED"),
                text("institutionVenue", "institution_venue", "Organizing institution or venue", 200, true),
                date("startDate", "start_date", "Start date", true),
                date("endDate", "end_date", "End date", true),
                integer("days", "days", "Number of days", 1, 366, true))
                .dateRange("startDate", "endDate"));
        add(SectionSpec.list("certifications", "certifications",
                choice("platform", "platform", "Platform", true, "NPTEL", "SWAYAM", "COURSERA", "OTHER"),
                text("title", "title", "Course title", 300, true),
                date("startDate", "start_date", "Start date", true),
                date("endDate", "end_date", "End date", true),
                text("durationWeeksHours", "duration_weeks_hours", "Duration (weeks or hours)", 40, false),
                text("gradeScore", "grade_score", "Grade or score", 40, false))
                .dateRange("startDate", "endDate"));

        // 4. Administrative responsibilities
        add(SectionSpec.list("administrative-roles", "administrative_roles",
                choice("scope", "scope", "Level", true, "INSTITUTE", "DEPARTMENT"),
                text("role", "role", "Role", 160, true),
                text("description", "description", "Responsibility or description", 1000, false),
                text("period", "period", "Period", 60, true)));
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
                text("authorPosition", "author_position", "Author position", 40, true),
                text("journal", "journal", "Journal", 200, true),
                text("volumeIssuePage", "volume_issue_page", "Volume, issue and page numbers", 80, false),
                monthYear("monthYear", "month_year", "Month and year", true),
                choice("indexing", "indexing", "Indexing", true, "SCI_SCIE", "SCOPUS", "UGC_CARE_ABDC", "OTHERS"),
                text("doiIssn", "doi_issn", "DOI or ISSN", 80, false))
                .summary(Summaries::journals));
        add(SectionSpec.list("conference-papers", "conference_papers",
                text("title", "title", "Title", 400, true),
                text("conference", "conference", "Conference", 200, true),
                choice("level", "level", "Level", true, "NAT", "INTL"),
                monthYear("monthYear", "month_year", "Month and year", true),
                text("venue", "venue", "Venue", 200, false),
                text("doiIndexedIn", "doi_indexed_in", "DOI or indexed in", 120, false),
                integer("citations", "citations", "Number of citations", 0, 1000000, true)));
        add(SectionSpec.list("research-metrics", "research_metrics",
                choice("platform", "platform", "Platform", true, "GOOGLE_SCHOLAR", "SCOPUS", "WEB_OF_SCIENCE"),
                integer("totalPublications", "total_publications", "Total publications", 0, 1000000, true),
                integer("totalCitations", "total_citations", "Total citations", 0, 10000000, true),
                integer("hIndex", "h_index", "h-index", 0, 1000, true),
                integer("i10Index", "i10_index", "i10-index", 0, 100000, true))
                .uniqueBy("platform"));
        add(SectionSpec.list("research-scholars", "research_scholars",
                text("name", "name", "Scholar name", 120, true),
                choice("degree", "degree", "Degree", true, "PHD", "MTECH", "MBA"),
                text("universityRegNo", "university_reg_no", "University and registration number", 120, false),
                choice("status", "status", "Status", true, "REGISTERED", "SUBMITTED", "AWARDED"),
                year("year", "year", "Year", true)));
        add(SectionSpec.single("phd-progress", "phd_progress",
                text("universityCenter", "university_center", "University or centre", 200, false),
                year("registrationYear", "registration_year", "Year of registration", false),
                choice("stage", "stage", "Current stage", false, "COURSE_WORK", "COMPREHENSIVE_PROPOSAL",
                        "SYNOPSIS", "THESIS_SUBMITTED", "VIVA_COMPLETED"),
                text("progress", "progress", "Progress during the year", 1000, false)));

        // 6. Funded projects / consultancy
        add(SectionSpec.list("funded-projects", "funded_projects",
                text("title", "title", "Title", 300, true),
                choice("role", "role", "Role", true, "PI", "CO_PI"),
                text("team", "team", "Team", 300, false),
                choice("type", "type", "Type", true, "RESEARCH", "CONSULTANCY"),
                text("fundingAgencyClient", "funding_agency_client", "Funding agency or client", 200, true),
                decimal("amount", "amount", "Amount (Rs.)", 0, 1e11, 2, true),
                date("startDate", "start_date", "Start date", true),
                date("endDate", "end_date", "End date", true),
                choice("status", "status", "Status", true, "SANCTIONED", "APPLIED"),
                year("year", "year", "Year", true))
                .dateRange("startDate", "endDate")
                .summary(Summaries::fundedProjects));

        // 7. Patents, books & IPR
        add(SectionSpec.list("patents-ipr", "patents_ipr",
                text("applicantInventors", "applicant_inventors", "Applicant or inventors", 300, true),
                text("title", "title", "Title", 300, true),
                text("applicationPatentNo", "application_patent_no", "Application or patent number", 80, false),
                choice("type", "type", "Type", true, "DESIGN", "UTILITY", "COPYRIGHT"),
                choice("status", "status", "Status", true, "FILED", "PUBLISHED", "GRANTED"),
                date("recordDate", "record_date", "Date", true))
                .summary(Summaries::patents));
        add(SectionSpec.list("books", "books",
                text("authors", "authors", "Authors", 300, true),
                text("title", "title", "Title of book or chapter", 300, true),
                text("publisher", "publisher", "Publisher", 160, true),
                text("isbn", "isbn", "ISBN", 24, false),
                monthYear("monthYear", "month_year", "Month and year", true),
                choice("type", "type", "Type", true, "BOOK", "CHAPTER"))
                .summary(Summaries::books));

        // 8. Outreach
        add(SectionSpec.list("outreach", "outreach",
                choice("role", "role", "Role", true, "CONFERENCE_SESSION_CHAIR", "EXPERT_LECTURE_DELIVERED",
                        "RESOURCE_PERSON", "EDITORIAL_BOARD_MEMBER", "JOURNAL_REVIEWER", "EXTERNAL_EXAMINER",
                        "EXTERNAL_THESIS_EVALUATED", "VISITING_RESEARCHER", "INDUSTRY_INTERACTION_MOU",
                        "INTERNATIONAL_CONFERENCE_ATTENDED", "OTHERS"),
                text("eventActivity", "event_activity", "Event or activity", 300, true),
                text("organization", "organization", "Name of organization or institution", 200, true),
                text("venue", "venue", "Venue", 200, false),
                date("eventDate", "event_date", "Date", true)));

        // 9. Professional memberships, awards & recognitions
        add(SectionSpec.list("memberships-awards", "memberships_awards",
                text("item", "item", "Membership, award or recognition", 300, true),
                text("awardingBody", "awarding_body", "Professional or awarding body", 200, true),
                choice("level", "level", "Level", true, "INST", "STATE", "NAT", "INTL"),
                year("year", "year", "Year", true)));

        // 10. Any other contributions
        add(SectionSpec.single("other-contributions", "other_contributions",
                text("departmentContribution", "department_contribution", "Contribution at department level", 5000, false),
                text("instituteContribution", "institute_contribution", "Contribution at institute level", 5000, false)));
    }

    public static Map<String, SectionSpec> all() { return Collections.unmodifiableMap(ALL); }

    public static SectionSpec get(String key) { return ALL.get(key); }
}
