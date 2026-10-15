import { duration } from "./format";
import { fieldLabel } from "./labels";
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
  /** Text-field suggestions that are fixed in this file (free text is still allowed); the lists in the database are preferred. */
  suggestions?: Record<string, string[]>;
  /** Choice fields shown as radio buttons (one of a few options) instead of a drop-down. */
  radios?: string[];
  emptyText?: string;
  /** Rendered as a fixed grid of rows (one per allowed value of this field) instead of an add/remove list. */
  fixedBy?: string;
  /** Fields of a single-record section that are kept but not shown (an older free-text field replaced by a list). */
  hiddenFields?: string[];
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
    sections: [
      { key: "general-information", title: "Your details", hiddenFields: ["researchIds"] },
      {
        key: "research-profiles",
        title: "Research profile IDs",
        addLabel: "Add profile",
        columns: ["platform", "profileId"],
        headers: { platform: "Profile", profileId: "ID or link" },
        hint: "Choose ORCID, Scopus, Google Scholar or Vidwan, enter your ID or link, and add the profiles one at a time. Each profile can be added once.",
        maxRecords: 4,
        emptyText: "No research profile has been added yet.",
      },
    ],
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
          { header: "Course Code & Course (Theory / Lab)", render: (r) => `${s(r.courseCode)} · ${s(r.courseName)} (${fieldLabel("teaching-courses", "courseType", r.courseType)})` },
          { header: "Program & Branch", render: (r) => `${fieldLabel("teaching-courses", "program", r.program)} · ${fieldLabel("teaching-courses", "branch", r.branch)}` },
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
        columns: [
          "title",
          "mode",
          "institutionVenue",
          { header: "Duration (No. of Days)", render: (r) => (Number(r.days) >= 5 ? s(r.days) : `${s(r.days)} (fewer than 5 days: no marks)`) },
        ],
        headers: { title: "Title of the Program", mode: "Mode", institutionVenue: "Organizing Institution / Venue" },
        hint: "A workshop, FDP, seminar or training program of five days or more earns 2.5 marks. The total for this part is limited to 5 marks, however many programs are entered. A program of fewer than five days is recorded but earns no marks.",
        emptyText: "No programs added yet.",
      },
      {
        key: "certifications",
        title: "Certifications",
        addLabel: "Add certification",
        columns: [
          { header: "Platform", render: (r) => (r.platform === "OTHER" && s(r.platformOther) ? s(r.platformOther) : fieldLabel("certifications", "platform", r.platform)) },
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
        hint: "Choose the role from the list. If it is not there, choose Other and type it.",
        emptyText: "No institute-level roles added yet.",
      },
      {
        key: "administrative-roles",
        title: "Department level roles",
        scope: { field: "scope", value: "DEPARTMENT" },
        addLabel: "Add department-level role",
        columns: ["role", "description", { header: "Duration (From–To)", render: (r) => duration(r, "fromDate", "toDate") }],
        headers: { role: "Role", description: "Responsibility / Description" },
        hint: "Choose the role from the list. If it is not there, choose Other and type it.",
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
          { header: "Title of Paper (your position in author list)", render: (r) => `${s(r.title)} (${fieldLabel("journal-publications", "authorPosition", r.authorPosition)})` },
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
        columns: ["title", "conference", "level", "monthYear", "venue", "doiIndexedIn"],
        headers: { title: "Title of the Paper", conference: "Name of the Conference", level: "Level", monthYear: "Month & Year", venue: "Venue", doiIndexedIn: "DOI / Indexed in" },
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
          { header: "Role (PI / Co-PI) & Team", render: (r) => [fieldLabel("funded-projects", "role", r.role), s(r.team)].filter(Boolean).join(" · ") },
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
        hint: "A sanctioned project earns 50m for each. An applied project earns 5m for each, and the applied projects together earn at most 10m in the academic year (you can still record more). Only projects whose year falls in the academic year being appraised are accepted.",
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
        addLabel: "Add book or book chapter",
        columns: ["authors", "title", "publisher", "isbn", "monthYear", "type"],
        headers: { authors: "Authors", title: "Title of the Book / Chapter", publisher: "Publisher", isbn: "ISBN", monthYear: "Month & Year", type: "Type (Book / Book Chapter)" },
        summary: [
          { key: "books", label: "No. of Books Published", format: "count" },
          { key: "chapters", label: "No. of Book Chapters Published", format: "count" },
        ],
        emptyText: "No books or book chapters added yet.",
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
  {
    slug: "action-plan",
    number: "12",
    title: "Action Plan for the Next 2 Years",
    formRef: "Part B · 11",
    description: "Where you plan to take your teaching, research and contributions over the next two years. Required before you can submit; up to 2,000 words.",
    sections: [{ key: "action-plan", title: "Action Plan for the Next 2 Years" }],
  },
];

export const pageBySlug = (slug: string) => PAGES.find((p) => p.slug === slug);

export function nextPrev(slug: string): { prev: PageUi | null; next: PageUi | null } {
  const i = PAGES.findIndex((p) => p.slug === slug);
  return { prev: i > 0 ? PAGES[i - 1] : null, next: i >= 0 && i < PAGES.length - 1 ? PAGES[i + 1] : null };
}

