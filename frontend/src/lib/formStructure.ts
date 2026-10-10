import { duration } from "./format";
import { enumLabel } from "./labels";
import type { Rec } from "./types";

/**
 * Presentation only: page grouping, headings (the official form's wording), table columns and hints.
 * Limits, labels, required flags and allowed values are NOT here; they come from /api/sections/meta so the
 * browser can never disagree with the server.
 */

export type Column = string | { header: string; render: (r: Rec) => string };

export type SummaryFormat = "count" | "number" | "percent" | "rupees";

export interface SectionUi {
  key: string;
  title: string;
  description?: string;
  addLabel?: string;
  columns?: Column[];
  headers?: Record<string, string>;
  /** Show only records whose field equals value; new records get that value and the field is hidden. */
  scope?: { field: string; value: string };
  hint?: string;
  /**
   * Fields offered as drop-down filters above the list. When omitted, every choice (enumerated) field shown as a
   * column is offered; either way a filter appears only where the records actually differ in that field.
   */
  filters?: string[];
  /** No more than this many records can be added; the Add button goes once it is reached. */
  maxRecords?: number;
  /** Filters on something worked out from a record rather than stored in it (odd or even semester). */
  derivedFilters?: { name: string; label: string; options: { value: string; label: string }[]; of: (r: Rec) => string }[];
  /** Show the list as one table per value of this field (for example per semester), headed by `title`. */
  groupBy?: { field: string; title: (value: string, count: number) => string };
  summary?: { key: string; label: string; format: SummaryFormat }[];
  /** Text-field suggestions (free text is still allowed). */
  suggestions?: Record<string, string[]>;
  /** Choice fields shown as radio buttons (one of a few options) instead of a drop-down. */
  radios?: string[];
  /** Helper text shown under a field in the form, by field name. */
  fieldHints?: Record<string, string>;
  emptyText?: string;
  /** Rendered as a fixed grid of rows (one per allowed value of this field) instead of an add/remove list. */
  fixedBy?: string;
  /** Single-record section shown only while this condition on another section holds. */
  gate?: { section: string; field: string; equals: string; message: string };
}

export interface PageUi {
  slug: string;
  number: string;
  title: string;
  formRef: string;
  description: string;
  /** Show the read-only Part A identity block (name, employee ID...) above the sections. */
  identity?: boolean;
  /** The score-sheet criterion this page's entries earn marks for; its marks are shown live above the sections. */
  criterion?: string;
  sections: SectionUi[];
}

const s = (v: unknown) => (v == null ? "" : String(v));

export const PAGES: PageUi[] = [
  {
    slug: "general",
    number: "01",
    title: "General Information",
    formRef: "Part A",
    description: "Confirm your details for this academic year. They start from your profile and can be corrected here.",
    identity: true,
    sections: [{ key: "general-information", title: "Your details" }],
  },
  {
    slug: "teaching",
    criterion: "TEACHING_LEARNING",
    number: "02",
    title: "Teaching & Learning",
    formRef: "Part B · 1",
    description: "Courses you handled during the academic year, with pass percentage and feedback percentages.",
    sections: [
      {
        key: "teaching-courses",
        title: "Courses handled during the Academic Year",
        addLabel: "Add course",
        columns: [
          { header: "Course Code & Name (Theory / Lab)", render: (r) => `${s(r.courseCode)} · ${s(r.courseName)} (${enumLabel(s(r.courseType))})` },
          { header: "Program & Branch", render: (r) => `${enumLabel(s(r.program))} · ${enumLabel(s(r.branch))}` },
          "courseRole",
          "semester",
          "section",
          "hoursPerWeek",
          "passPercentage",
          "phase1Feedback",
          "phase2Feedback",
        ],
        radios: ["courseRole"],
        headers: { courseRole: "Role", semester: "Sem", section: "Sec", hoursPerWeek: "Hours / Week", passPercentage: "Pass %", phase1Feedback: "Phase-1 Feedback %", phase2Feedback: "Phase-2 Feedback %" },
        filters: ["courseType", "courseRole", "semester", "section", "program", "branch"],
        derivedFilters: [
          {
            name: "semesterType",
            label: "Odd / even semester",
            options: [{ value: "odd", label: "Odd semesters (1, 3, 5, 7)" }, { value: "even", label: "Even semesters (2, 4, 6, 8)" }],
            of: (r) => (Number(r.semester) % 2 === 1 ? "odd" : "even"),
          },
        ],
        maxRecords: 8,
        groupBy: { field: "semester", title: (v, n) => `Semester ${v} · ${n} course${n === 1 ? "" : "s"}` },
        summary: [
          { key: "courseCount", label: "Courses added (at most 8)", format: "count" },
          { key: "totalHoursPerWeek", label: "Total teaching load (Hrs/Week)", format: "number" },
          { key: "averagePassPercentage", label: "Average Pass %", format: "percent" },
          { key: "averageFeedback", label: "Average Feedback % (Ph-1 & Ph-2)", format: "percent" },
        ],
        emptyText: "No courses added yet.",
      },
    ],
  },
  {
    slug: "mentoring",
    criterion: "STUDENT_MENTORING",
    number: "03",
    title: "Mentoring & Projects",
    formRef: "Part B · 2",
    description: "Student mentoring, achievements of your mentees, and projects you guided.",
    sections: [
      { key: "mentoring-summary", title: "Total No. of Students Mentored" },
      {
        key: "student-achievements",
        title: "Student achievements (mentees / project students)",
        addLabel: "Add achievement",
        columns: [
          { header: "Student Name & Roll No.", render: (r) => `${s(r.studentName)} (${s(r.rollNo)})` },
          "achievement",
          "level",
          "monthYear",
        ],
        headers: { achievement: "Achievement", level: "Level", monthYear: "Month & Year" },
        hint: "Achievement may be Placement, Hackathon, Paper, Award, GATE / CAT / GRE, or Start-up.",
        emptyText: "No achievements added yet.",
      },
      {
        key: "student-projects",
        title: "Student projects guided (Diploma / UG / PG)",
        addLabel: "Add project",
        columns: ["level", "title", "studentCount", "outcome"],
        headers: { level: "Level", title: "Project Title", studentCount: "No. of Students", outcome: "Outcome" },
        emptyText: "No projects added yet.",
      },
    ],
  },
  {
    slug: "fdp",
    criterion: "FDP_CERTIFICATIONS",
    number: "04",
    title: "FDPs & Certifications",
    formRef: "Part B · 3",
    description: "Workshops, FDPs, seminars and training programs attended, and certifications earned.",
    sections: [
      {
        key: "fdps",
        title: "Workshops / FDPs / Seminars / Training programs attended",
        addLabel: "Add program",
        columns: ["title", "mode", "institutionVenue", "days"],
        headers: { title: "Title of the Program", mode: "Mode", institutionVenue: "Organizing Institution / Venue", days: "Duration (No. of Days)" },
        hint: "A program of 5 days or more earns the 5 marks (once, however many you add). Shorter programs are recorded but earn no marks.",
        emptyText: "No programs added yet.",
      },
      {
        key: "certifications",
        title: "Certifications",
        addLabel: "Add certification",
        columns: [
          { header: "Platform", render: (r) => (r.platform === "OTHER" && s(r.platformOther) ? s(r.platformOther) : enumLabel(s(r.platform))) },
          "title",
          "durationWeeks",
          "gradeScore",
        ],
        headers: { title: "Title of the Course", durationWeeks: "Duration (Weeks)", gradeScore: "Grade / Score" },
        hint: "Any valid certification earns the full 10 marks (once, however many you add). Give the duration in whole weeks.",
        emptyText: "No certifications added yet.",
      },
    ],
  },
  {
    slug: "administration",
    criterion: "ADMINISTRATIVE",
    number: "05",
    title: "Administration",
    formRef: "Part B · 4",
    description: "Institute-level and department-level responsibilities, and events you organized or coordinated.",
    sections: [
      {
        key: "administrative-roles",
        title: "Institute level roles",
        scope: { field: "scope", value: "INSTITUTE" },
        addLabel: "Add institute-level role",
        columns: ["role", "description", { header: "Duration (From–To)", render: (r) => duration(r, "fromDate", "toDate") }],
        headers: { role: "Role", description: "Responsibility / Description" },
        hint: "For example: NAAC / NBA criterion coordinator, IQAC, Exam Cell, Training & Placement, NSS / NCC, Anti-Ragging.",
        suggestions: { role: ["NAAC / NBA criterion coordinator", "IQAC", "Exam Cell", "Training & Placement", "NSS / NCC", "Anti-Ragging"] },
        fieldHints: { role: "Select or enter your role" },
        emptyText: "No institute-level roles added yet.",
      },
      {
        key: "administrative-roles",
        title: "Department level roles",
        scope: { field: "scope", value: "DEPARTMENT" },
        addLabel: "Add department-level role",
        columns: ["role", "description", { header: "Duration (From–To)", render: (r) => duration(r, "fromDate", "toDate") }],
        headers: { role: "Role", description: "Responsibility / Description" },
        hint: "For example: Class Coordinator, Module Coordinator, Lab In-charge, Time-table In-charge, NAAC / NBA criterion coordinator, IQAC, Exam Cell, Training & Placement, NSS / NCC, Anti-Ragging.",
        suggestions: { role: ["Class Coordinator", "Module Coordinator", "Lab In-charge", "Time-table In-charge", "NAAC / NBA criterion coordinator", "IQAC", "Exam Cell", "Training & Placement", "NSS / NCC", "Anti-Ragging"] },
        fieldHints: { role: "Select or enter your role" },
        emptyText: "No department-level roles added yet.",
      },
      {
        key: "events",
        title: "Student workshops / events / tours / trainings / guest lectures organized / coordinated",
        addLabel: "Add event",
        columns: ["activityType", "role", "title", { header: "Duration (From–To)", render: (r) => duration(r) }, "beneficiaries"],
        headers: { activityType: "Type of Activity", role: "Role", title: "Title / Details of the Event", beneficiaries: "No. of Beneficiaries" },
        emptyText: "No events added yet.",
      },
    ],
  },
  {
    slug: "research",
    criterion: "RESEARCH_PUBLICATIONS",
    number: "06",
    title: "Research & Publications",
    formRef: "Part B · 5",
    description: "Journal and conference papers, profile metrics, research guidance and your own Ph.D. progress.",
    sections: [
      {
        key: "journal-publications",
        title: "Journal publications",
        addLabel: "Add publication",
        columns: [
          { header: "Title of Paper (your position in author list)", render: (r) => `${s(r.title)} (${enumLabel(s(r.authorPosition))})` },
          "journal",
          "volumeIssuePage",
          "monthYear",
          "indexing",
          "doiIssn",
        ],
        headers: { journal: "Name of the Journal", volumeIssuePage: "Vol. / Issue / Page No.", monthYear: "Month & Year", indexing: "Indexing", doiIssn: "DOI / ISSN" },
        summary: [
          { key: "sciScie", label: "SCI/SCIE", format: "count" },
          { key: "scopus", label: "Scopus", format: "count" },
          { key: "ugcCareAbdc", label: "UGC-CARE / ABDC", format: "count" },
          { key: "others", label: "Others", format: "count" },
        ],
        emptyText: "No journal publications added yet.",
      },
      {
        key: "conference-papers",
        title: "Conference papers published",
        addLabel: "Add conference paper",
        columns: ["title", "conference", "level", "monthYear", "venue", "doiIndexedIn", "citations"],
        headers: { title: "Title of the Paper", conference: "Name of the Conference", level: "Level", monthYear: "Month & Year", venue: "Venue", doiIndexedIn: "DOI / Indexed in", citations: "No. of Citations" },
        emptyText: "No conference papers added yet.",
      },
      { key: "research-metrics", title: "Research profile metrics", fixedBy: "platform" },
      {
        key: "research-scholars",
        title: "Research scholars / PG dissertations guided",
        addLabel: "Add scholar",
        columns: ["name", "degree", "universityRegNo", "status", "year"],
        headers: { name: "Name of Scholar", degree: "Degree", universityRegNo: "University / Reg. No.", status: "Status", year: "Year" },
        emptyText: "No scholars added yet.",
      },
      {
        key: "phd-progress",
        title: "Own Ph.D. progress (if pursuing)",
        gate: {
          section: "general-information",
          field: "phdStatus",
          equals: "PURSUING",
          message: "This applies only if your Ph.D. status is Pursuing. You can change your status under General Information.",
        },
      },
    ],
  },
  {
    slug: "funded-projects",
    criterion: "FUNDED_PROJECTS",
    number: "07",
    title: "Funded Projects / Consultancy",
    formRef: "Part B · 6",
    description: "Research projects and consultancy, sanctioned or applied.",
    sections: [
      {
        key: "funded-projects",
        title: "Funded projects and consultancy",
        addLabel: "Add project",
        columns: [
          "title",
          { header: "Role (PI / Co-PI) & Team", render: (r) => [enumLabel(s(r.role)), s(r.team)].filter(Boolean).join(" · ") },
          "type",
          "fundingAgencyClient",
          "amount",
          { header: "Duration (From–To)", render: (r) => duration(r) },
          "status",
          "year",
        ],
        headers: { title: "Title", type: "Type", fundingAgencyClient: "Funding Agency / Client", amount: "Amount (Rs.)", status: "Status", year: "Year" },
        filters: ["status", "type", "role", "year"],
        summary: [
          { key: "sanctioned", label: "Sanctioned", format: "count" },
          { key: "applied", label: "Applied", format: "count" },
          { key: "totalAmountSanctioned", label: "Total Amount Sanctioned (Rs.)", format: "rupees" },
          { key: "consultancyRevenue", label: "Consultancy Revenue (Rs.)", format: "rupees" },
        ],
        hint: "Only projects whose year falls in the academic year being appraised are accepted, and only those earn marks.",
        emptyText: "No projects added yet.",
      },
    ],
  },
  {
    slug: "ipr",
    criterion: "PATENTS_BOOKS_IPR",
    number: "08",
    title: "Patents, Books & IPR",
    formRef: "Part B · 7",
    description: "Patents, designs, copyrights, books and book chapters.",
    sections: [
      {
        key: "patents-ipr",
        title: "Patents / Designs / Copyrights",
        addLabel: "Add patent or design",
        columns: ["applicantInventors", { header: "Title & Application / Patent No.", render: (r) => [s(r.title), s(r.applicationPatentNo)].filter(Boolean).join(" · ") }, "type", "status", "recordDate"],
        headers: { applicantInventors: "Applicant / Inventor(s)", type: "Type", status: "Status", recordDate: "Date (DD-MM-YYYY)" },
        summary: [
          { key: "filed", label: "Filed", format: "count" },
          { key: "published", label: "Published", format: "count" },
          { key: "granted", label: "Granted", format: "count" },
        ],
        emptyText: "No patents or designs added yet.",
      },
      {
        key: "books",
        title: "Books / Book chapters",
        addLabel: "Add book or chapter",
        columns: ["authors", "title", "publisher", "isbn", "monthYear", "type"],
        headers: { authors: "Authors", title: "Title of the Book / Chapter", publisher: "Publisher", isbn: "ISBN", monthYear: "Month & Year", type: "Type (Book / Chapter)" },
        summary: [
          { key: "books", label: "No. of Books Published", format: "count" },
          { key: "chapters", label: "No. of Book Chapters Published", format: "count" },
        ],
        emptyText: "No books or chapters added yet.",
      },
    ],
  },
  {
    slug: "outreach",
    criterion: "OUTREACH",
    number: "09",
    title: "Outreach",
    formRef: "Part B · 8",
    description: "Lectures, sessions, reviews, examinations and other outreach.",
    sections: [
      {
        key: "outreach",
        title: "Outreach",
        addLabel: "Add outreach activity",
        columns: ["role", "eventActivity", "organization", "venue", "eventDate"],
        headers: { role: "Role", eventActivity: "Event / Activity", organization: "Name of the Organization / Institution", venue: "Venue", eventDate: "Date" },
        emptyText: "No outreach activities added yet.",
      },
    ],
  },
  {
    slug: "awards",
    criterion: "MEMBERSHIPS_AWARDS",
    number: "10",
    title: "Memberships, Awards & Recognitions",
    formRef: "Part B · 9",
    description: "Professional memberships, awards and recognitions.",
    sections: [
      {
        key: "memberships-awards",
        title: "Professional memberships, awards & recognitions",
        addLabel: "Add entry",
        columns: ["item", "awardingBody", "level", "year"],
        headers: { item: "Membership / Award / Recognition", awardingBody: "Professional / Awarding Body", level: "Level", year: "Year" },
        hint: "Awarding bodies such as IEEE, CSI, ISTE, IE(I).",
        suggestions: { awardingBody: ["IEEE", "CSI", "ISTE", "IE(I)"] },
        emptyText: "No entries added yet.",
      },
    ],
  },
  {
    slug: "other",
    number: "11",
    title: "Other Contributions",
    formRef: "Part B · 10",
    description: "Anything else you contributed at department or institute level.",
    sections: [{ key: "other-contributions", title: "Any Other Contributions" }],
  },
];

export const pageBySlug = (slug: string) => PAGES.find((p) => p.slug === slug);

export function nextPrev(slug: string): { prev: PageUi | null; next: PageUi | null } {
  const i = PAGES.findIndex((p) => p.slug === slug);
  return { prev: i > 0 ? PAGES[i - 1] : null, next: i >= 0 && i < PAGES.length - 1 ? PAGES[i + 1] : null };
}

