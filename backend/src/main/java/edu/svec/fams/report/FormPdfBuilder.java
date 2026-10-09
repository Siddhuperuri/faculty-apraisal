package edu.svec.fams.report;

import static edu.svec.fams.report.ReportLabels.date;
import static edu.svec.fams.report.ReportLabels.label;
import static edu.svec.fams.report.ReportLabels.money;
import static edu.svec.fams.report.ReportLabels.monthYear;
import static edu.svec.fams.report.ReportLabels.number;
import static edu.svec.fams.report.ReportLabels.pdf;

import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.ColumnText;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfGState;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfTemplate;
import com.lowagie.text.pdf.PdfWriter;
import edu.svec.fams.appraisal.AppraisalService.AppraisalView;
import edu.svec.fams.scoring.Criteria;
import edu.svec.fams.scoring.ScoreService.Component;
import edu.svec.fams.scoring.ScoreService.ScoreRow;
import edu.svec.fams.scoring.ScoringRules;
import edu.svec.fams.section.SectionService.SectionView;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Draws the college's "Faculty Self Appraisal & Assessment Report" from saved data, following the structure,
 * headings and column names of the official form: letterhead, Part A, Part B items 1-11, the declaration, the HoD,
 * Principal and Director Technical boxes and Annexure A, followed by Annexure B (the scoring components of the faculty
 * member's cadre). Signature, seal and date lines are left blank on purpose: they are completed on paper. Everything is text from the database, never a screenshot.
 */
final class FormPdfBuilder {

    /** Maximum marks of one cadre, for Annexure A. */
    record Cadre(String code, String name, Map<String, Integer> marks) {}

    record Data(AppraisalView view, String departmentCode, String departmentName, String cadreCode,
                Map<String, SectionView> sections, List<Cadre> annexure,
                String hodRecommendation, OffsetDateTime hodAt, String deanRemarks, OffsetDateTime deanAt,
                String vpRemarks, OffsetDateTime vpAt, String principalRemarks, OffsetDateTime principalAt,
                String directorRemarks, OffsetDateTime directorAt,
                boolean approved, String statusLabel, OffsetDateTime generatedAt) {}

    private record Col(String header, float weight, Function<Map<String, Object>, String> value, boolean right) {}

    // ---- the form's look ----
    private static final Color NAVY = new Color(0x1F, 0x38, 0x64);
    private static final Color BLUE = new Color(0x00, 0x70, 0xC0);
    private static final Color HEADER_BLUE = new Color(0xDC, 0xE6, 0xF2);
    private static final Color PEACH = new Color(0xFD, 0xE9, 0xD9);
    private static final Color GREY = new Color(0x7F, 0x7F, 0x7F);
    private static final Color PALE = new Color(0xF2, 0xF2, 0xF2);
    private static final Color NAAC = new Color(0xC0, 0x00, 0x7A);

    private static final Font BODY = FontFactory.getFont(FontFactory.TIMES_ROMAN, 8.5f, Font.NORMAL, Color.BLACK);
    private static final Font BODY_BOLD = FontFactory.getFont(FontFactory.TIMES_BOLD, 8.5f, Font.NORMAL, Color.BLACK);
    private static final Font ITALIC = FontFactory.getFont(FontFactory.TIMES_ITALIC, 8f, Font.NORMAL, new Color(0x40, 0x40, 0x40));
    private static final Font TH = FontFactory.getFont(FontFactory.TIMES_BOLD, 8f, Font.NORMAL, Color.BLACK);
    private static final Font SANS = FontFactory.getFont(FontFactory.HELVETICA, 7.5f, Font.NORMAL, Color.BLACK);
    private static final Font BAR = FontFactory.getFont(FontFactory.TIMES_BOLD, 9f, Font.NORMAL, Color.WHITE);
    private static final Font HEADING = FontFactory.getFont(FontFactory.TIMES_BOLD, 10f, Font.NORMAL, NAVY);
    private static final Font SUB = FontFactory.getFont(FontFactory.TIMES_BOLD, 9f, Font.NORMAL, Color.BLACK);
    private static final Font BOX = FontFactory.getFont(FontFactory.ZAPFDINGBATS, 8f, Font.NORMAL, Color.BLACK);

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    private final Data d;
    private PdfWriter writer;
    private final List<Pending> pending = new ArrayList<>();

    /** A heading or lead-in line waiting to be placed together with the table that follows it. */
    private record Pending(Paragraph paragraph, float height) {}

    FormPdfBuilder(Data data) { this.d = data; }

    // ====================================================================================================
    // Entry point
    // ====================================================================================================

    byte[] build() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 40, 40, 60, 48);
        try {
            writer = PdfWriter.getInstance(doc, out);
            writer.setPageEvent(new PageDecor());
            doc.addTitle("Faculty Self Appraisal & Assessment Report " + pdf(d.view().academicYear()));
            doc.addAuthor("Sri Vasavi Engineering College (Autonomous)");
            doc.addSubject(pdf(d.view().facultyName()) + " (" + pdf(d.view().employeeId()) + ")");
            doc.addCreator("Faculty Appraisal Management System");
            doc.open();

            letterhead(doc);
            partA(doc);
            partB(doc);
            declarationAndReview(doc);
            annexureA(doc);
            annexureB(doc);
            doc.close();
        } catch (DocumentException | IOException e) {
            throw new IllegalStateException("The report could not be produced", e);
        }
        return out.toByteArray();
    }

    // ====================================================================================================
    // Letterhead and Part A
    // ====================================================================================================

    private void letterhead(Document doc) throws DocumentException, IOException {
        PdfPTable head = new PdfPTable(new float[] {1.1f, 6f});
        head.setWidthPercentage(100);

        PdfPCell logo = new PdfPCell();
        logo.setBorder(Rectangle.NO_BORDER);
        try (InputStream in = FormPdfBuilder.class.getResourceAsStream("/report/svec-logo.png")) {
            if (in != null) {
                Image img = Image.getInstance(in.readAllBytes());
                img.scaleToFit(62, 62);
                logo.addElement(img);
            }
        }
        head.addCell(logo);

        PdfPCell text = new PdfPCell();
        text.setBorder(Rectangle.NO_BORDER);
        text.addElement(centered("Tel: 08818-284577, 284355 Ext: 321;  Fax: 08818-284577     Visit us at: www.srivasaviengg.ac.in",
                FontFactory.getFont(FontFactory.HELVETICA, 6.8f, Font.NORMAL, Color.BLACK)));
        text.addElement(centered("SRI VASAVI ENGINEERING COLLEGE (AUTONOMOUS)",
                FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14.5f, Font.NORMAL, BLUE)));
        text.addElement(centered("(Sponsored by Sri Vasavi Educational Society; Regd.No:898/2000)",
                FontFactory.getFont(FontFactory.HELVETICA, 7.2f, Font.NORMAL, Color.BLACK)));
        Paragraph naac = new Paragraph();
        naac.setAlignment(Element.ALIGN_CENTER);
        naac.add(new Chunk("Accredited ", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 7.8f)));
        naac.add(new Chunk("by ", FontFactory.getFont(FontFactory.HELVETICA, 7.8f)));
        naac.add(new Chunk("NAAC", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 7.8f, Font.NORMAL, NAAC)));
        naac.add(new Chunk(" with '", FontFactory.getFont(FontFactory.HELVETICA, 7.8f)));
        naac.add(new Chunk("A", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 7.8f, Font.NORMAL, BLUE)));
        naac.add(new Chunk("' Grade", FontFactory.getFont(FontFactory.HELVETICA, 7.8f)));
        text.addElement(naac);
        text.addElement(centered("Approved by AICTE, New Delhi and Permanently Affiliated to JNTUK, Kakinada",
                FontFactory.getFont(FontFactory.HELVETICA, 7.2f, Font.NORMAL, Color.BLACK)));
        text.addElement(centered("Pedatadepalli, TADEPALLIGUDEM – 534 101, W.G. Dist, (A.P.)",
                FontFactory.getFont(FontFactory.HELVETICA_BOLD, 7.2f, Font.NORMAL, Color.BLACK)));
        head.addCell(text);
        doc.add(head);

        // Double navy rule and the form's title.
        PdfPTable title = new PdfPTable(1);
        title.setWidthPercentage(100);
        title.setSpacingBefore(6);
        PdfPCell t = new PdfPCell(new Phrase("Faculty Self Appraisal & Assessment Report",
                FontFactory.getFont(FontFactory.TIMES_BOLD, 12.5f, Font.NORMAL, Color.BLACK)));
        t.setHorizontalAlignment(Element.ALIGN_CENTER);
        t.setPadding(5);
        t.setBorder(Rectangle.TOP | Rectangle.BOTTOM);
        t.setBorderColor(NAVY);
        t.setBorderWidthTop(1.6f);
        t.setBorderWidthBottom(0.8f);
        title.addCell(t);
        doc.add(title);

        if (!d.approved()) {
            Paragraph draft = new Paragraph(pdf("DRAFT – not yet approved (status: " + d.statusLabel() + "). "
                    + "This copy is for review and has no official standing."),
                    FontFactory.getFont(FontFactory.HELVETICA_BOLD, 7.5f, Font.NORMAL, new Color(0xB3, 0x26, 0x1E)));
            draft.setAlignment(Element.ALIGN_CENTER);
            draft.setSpacingBefore(4);
            doc.add(draft);
        }
    }

    private void partA(Document doc) throws DocumentException {
        doc.add(bar("PART A – GENERAL INFORMATION", 8));
        Map<String, Object> g = first("general-information");
        AppraisalView v = d.view();

        PdfPTable t = new PdfPTable(new float[] {2.1f, 3f, 2.3f, 2.6f});
        t.setWidthPercentage(100);
        t.setSpacingBefore(0);
        pair(t, "Name of the Faculty", v.facultyName());
        pair(t, "Academic Year", v.academicYear());
        pair(t, "Employee ID", v.employeeId());
        pair(t, "Contact No.", str(g, "contactNo"));

        t.addCell(labelCell("Department / Branch"));
        t.addCell(spanned(checkboxes(new String[][] {
                {"CE", "CE"}, {"ME", "ME"}, {"ECE", "ECE / ECT"}, {"EEE", "EEE"}, {"CSE", "CSE / IT"},
                {"AIML", "AIML / CAI / DS"}, {"BSH", "BSH"}, {"MBA", "MBA"}}, d.departmentCode(), d.departmentName()), 3));

        t.addCell(labelCell("Designation (Cadre)"));
        t.addCell(spanned(checkboxes(new String[][] {
                {"LECTURER", "Lecturer"}, {"ASST_PROF", "Assistant Professor"}, {"SR_ASST_PROF", "Senior Assistant Professor"},
                {"ASSOC_PROF", "Associate Professor"}, {"PROFESSOR", "Professor"}}, d.cadreCode(), v.cadre()), 3));

        pair(t, "Highest Qualification & Specialization", str(g, "qualificationSpecialization"));
        t.addCell(labelCell("Ph.D. Status"));
        t.addCell(valueCell(checkboxes(new String[][] {
                {"AWARDED", "Awarded"}, {"PURSUING", "Pursuing"}, {"NOT_APPLICABLE", "Not applicable"}},
                str(g, "phdStatus"), null)));

        pair(t, "Date of Joining (Institution)", date(g == null ? null : g.get("joiningDateInstitution")));
        pair(t, "Date of Joining (Present Designation)", date(g == null ? null : g.get("joiningDateDesignation")));

        t.addCell(labelCell("Total Experience (Years)"));
        t.addCell(spanned(new Phrase(pdf("Teaching: " + blank(number(g == null ? null : g.get("teachingExperienceYears")))
                + "        Industry: " + blank(number(g == null ? null : g.get("industryExperienceYears")))
                + "        Research: " + blank(number(g == null ? null : g.get("researchExperienceYears")))), BODY), 3));

        pair(t, "E-mail ID", v.email());
        pair(t, "ORCID / Scopus / Google Scholar / Vidwan ID", str(g, "researchIds"));
        doc.add(t);
    }

    // ====================================================================================================
    // Part B
    // ====================================================================================================

    private void partB(Document doc) throws DocumentException {
        doc.add(bar("PART B – ACADEMIC & PROFESSIONAL ACTIVITIES", 10));

        teaching(doc);
        mentoring(doc);
        fdps(doc);
        administration(doc);
        research(doc);
        funded(doc);
        ipr(doc);
        outreach(doc);
        awards(doc);
        other(doc);
        scoreSheet(doc);
    }

    private void teaching(Document doc) throws DocumentException {
        push(heading("1. Teaching & Learning:"));
        push(sub("Courses handled during the Academic Year"));
        grid(doc, List.of(
                new Col("Course Code & Name (Theory / Lab)", 20, r -> str(r, "courseCode") + " – " + str(r, "courseName") + " (" + label(r.get("courseType")) + ")", false),
                new Col("Program & Branch", 13, r -> str(r, "program") + ", " + str(r, "branch"), false),
                new Col("Sem", 4.5f, r -> str(r, "semester"), true),
                new Col("No. of Sections", 6.5f, r -> str(r, "sections"), true),
                new Col("Hours / Week", 6.5f, r -> number(r.get("hoursPerWeek")), true),
                new Col("Pass %", 6, r -> number(r.get("passPercentage")), true),
                new Col("Phase-1 Feedback %", 8, r -> number(r.get("phase1Feedback")), true),
                new Col("Phase-2 Feedback %", 8, r -> number(r.get("phase2Feedback")), true)),
                rows("teaching-courses"));
        Map<String, Number> s = summary("teaching-courses");
        doc.add(line("Total teaching load (Hrs/Week): " + dash(number(s.get("totalHoursPerWeek")))
                + "        Average Pass %: " + dash(number(s.get("averagePassPercentage")))
                + "        Average Feedback % (Ph-1 & Ph-2): " + dash(number(s.get("averageFeedback")))));
    }

    private void mentoring(Document doc) throws DocumentException {
        push(heading("2. Student Mentoring, Project Guidance & Achievements:"));
        Map<String, Object> m = first("mentoring-summary");
        push(line("(a) Total No. of Students Mentored : " + dash(str(m, "totalStudentsMentored"))));
        push(sub("Student achievements (mentees / project students)"));
        grid(doc, List.of(
                new Col("Student Name & Roll No.", 18, r -> str(r, "studentName") + " (" + str(r, "rollNo") + ")", false),
                new Col("Achievement (Placement / Hackathon / Paper / Award / GATE-CAT-GRE / Start-up)", 30, r -> str(r, "achievement"), false),
                new Col("Level (Inst / State / Nat / Intl)", 12, r -> label(r.get("level")), false),
                new Col("Month & Year", 10, r -> monthYear(r.get("monthYear")), false)),
                rows("student-achievements"));
        push(sub("(b) Student projects guided (Diploma/UG/PG)"));
        grid(doc, List.of(
                new Col("Level (Diploma / UG / PG )", 12, r -> label(r.get("level")), false),
                new Col("Project Title", 34, r -> str(r, "title"), false),
                new Col("No. of Students", 8, r -> str(r, "studentCount"), true),
                new Col("Outcome (Paper / Patent / Prototype / Competition)", 16, r -> label(r.get("outcome")), false)),
                rows("student-projects"));
    }

    private void fdps(Document doc) throws DocumentException {
        push(heading("3. FDPs / Certifications:"));
        push(sub("(a) Workshops / FDPs / Seminars / Training programs attended"));
        grid(doc, List.of(
                new Col("Title of the Program", 26, r -> str(r, "title"), false),
                new Col("Mode (Offline / Online / Blended)", 11, r -> label(r.get("mode")), false),
                new Col("Organizing Institution / Venue", 18, r -> str(r, "institutionVenue"), false),
                new Col("Duration (From–To)", 16, r -> range(r), false),
                new Col("No. of Days", 6, r -> str(r, "days"), true)),
                rows("fdps"));
        push(sub("(b) Certifications"));
        grid(doc, List.of(
                new Col("Platform (NPTEL / Swayam / Coursera / Other)", 14, r -> label(r.get("platform")), false),
                new Col("Title of the Course", 24, r -> str(r, "title"), false),
                new Col("Duration (From–To)", 16, r -> range(r), false),
                new Col("No. of Weeks / Hours", 10, r -> str(r, "durationWeeksHours"), false),
                new Col("Grade / Score", 9, r -> str(r, "gradeScore"), false)),
                rows("certifications"));
    }

    private void administration(Document doc) throws DocumentException {
        push(heading("4. Administrative Responsibilities:"));
        List<Col> roleCols = List.of(
                new Col("Role", 20, r -> str(r, "role"), false),
                new Col("Responsibility / Description", 44, r -> str(r, "description"), false),
                new Col("Period", 12, r -> str(r, "period"), false));
        push(sub("(a) Institute level roles"));
        push(note("e.g., NAAC/NBA criterion coordinator, IQAC, Exam Cell, Training & Placement,  NSS/NCC, Anti-Ragging.,  etc."));
        grid(doc, roleCols, rowsWhere("administrative-roles", "scope", "INSTITUTE"));
        push(sub("(b) Department level roles"));
        push(note("e.g., Class Coordinator, Module Coordinator,  Lab In-charge, Time-table In-charge , NAAC/NBA criterion coordinator, IQAC, "
                + "Exam Cell, Training & Placement,  NSS/NCC, Anti-Ragging.,  etc."));
        grid(doc, roleCols, rowsWhere("administrative-roles", "scope", "DEPARTMENT"));
        push(sub("(c) Student workshops / events / tours / trainings / guest lectures organized / coordinated"));
        grid(doc, List.of(
                new Col("Type of Activity", 14, r -> str(r, "activityType"), false),
                new Col("Role", 14, r -> str(r, "role"), false),
                new Col("Title / Details of the Event", 26, r -> str(r, "title"), false),
                new Col("Duration (From–To)", 14, r -> range(r), false),
                new Col("No. of Beneficiaries", 8, r -> str(r, "beneficiaries"), true)),
                rows("events"));
    }

    private void research(Document doc) throws DocumentException {
        push(heading("5. Research & Publications:"));
        push(sub("(a) Journal publications"));
        Map<String, Number> j = summary("journal-publications");
        push(line("SCI/SCIE: " + dash(number(j.get("sciScie"))) + "        Scopus: " + dash(number(j.get("scopus")))
                + "        UGC-CARE / ABDC: " + dash(number(j.get("ugcCareAbdc"))) + "        Others: " + dash(number(j.get("others")))));
        grid(doc, List.of(
                new Col("Title of Paper (mark your position in author list)", 24, r -> str(r, "title") + " (" + str(r, "authorPosition") + ")", false),
                new Col("Name of the Journal", 15, r -> str(r, "journal"), false),
                new Col("Vol. / Issue / Page No.", 10, r -> str(r, "volumeIssuePage"), false),
                new Col("Month & Year", 8, r -> monthYear(r.get("monthYear")), false),
                new Col("Indexing (SCI / Scopus / UGC-CARE / ABDC / Others)", 11, r -> label(r.get("indexing")), false),
                new Col("DOI/ISSN", 10, r -> str(r, "doiIssn"), false)),
                rows("journal-publications"));

        push(sub("(b) Conference papers presented"));
        grid(doc, List.of(
                new Col("Title of the Paper", 22, r -> str(r, "title"), false),
                new Col("Name of the Conference", 16, r -> str(r, "conference"), false),
                new Col("Level (Nat / Intl)", 7, r -> label(r.get("level")), false),
                new Col("Month & Year", 8, r -> monthYear(r.get("monthYear")), false),
                new Col("Venue", 10, r -> str(r, "venue"), false),
                new Col("DOI / Indexed in", 11, r -> str(r, "doiIndexedIn"), false),
                new Col("No. of Citations", 7, r -> str(r, "citations"), true)),
                rows("conference-papers"));

        push(sub("(c) Research profile metrics"));
        Map<String, Map<String, Object>> byPlatform = new LinkedHashMap<>();
        for (Map<String, Object> r : rows("research-metrics")) byPlatform.put(str(r, "platform"), r);
        List<Map<String, Object>> metricRows = new ArrayList<>();
        for (String p : new String[] {"GOOGLE_SCHOLAR", "SCOPUS", "WEB_OF_SCIENCE"}) {
            Map<String, Object> r = new LinkedHashMap<>(byPlatform.getOrDefault(p, Map.of()));
            r.put("platform", p);
            metricRows.add(r);
        }
        grid(doc, List.of(
                new Col("Platform", 16, r -> label(r.get("platform")), false),
                new Col("Total Publications", 14, r -> str(r, "totalPublications"), true),
                new Col("Total Citations", 14, r -> str(r, "totalCitations"), true),
                new Col("h-index", 12, r -> str(r, "hIndex"), true),
                new Col("i10-index", 12, r -> str(r, "i10Index"), true)),
                metricRows, false);

        push(sub("(d) Research guidance and own Ph.D. progress"));
        push(sub("(i) Research scholars / PG dissertations guided"));
        grid(doc, List.of(
                new Col("Name of Scholar", 18, r -> str(r, "name"), false),
                new Col("Degree (Ph.D. / M.Tech / MBA)", 14, r -> label(r.get("degree")), false),
                new Col("University / Reg. No.", 18, r -> str(r, "universityRegNo"), false),
                new Col("Status (Registered / Submitted / Awarded)", 18, r -> label(r.get("status")), false),
                new Col("Year", 6, r -> str(r, "year"), true)),
                rows("research-scholars"));

        push(sub("(ii) Own Ph.D. progress (if pursuing)"));
        Map<String, Object> p = first("phd-progress");
        PdfPTable t = new PdfPTable(new float[] {2.2f, 3f, 2.2f, 2.6f});
        t.setWidthPercentage(100);
        pair(t, "University / Center", str(p, "universityCenter"));
        pair(t, "Year of Registration", str(p, "registrationYear"));
        t.addCell(labelCell("Current Stage"));
        t.addCell(spanned(checkboxes(new String[][] {
                {"COURSE_WORK", "Course work"}, {"COMPREHENSIVE_PROPOSAL", "Comprehensive / Proposal"},
                {"SYNOPSIS", "Synopsis / Pre-Ph.D."}, {"THESIS_SUBMITTED", "Thesis submitted"},
                {"VIVA_COMPLETED", "Viva-voce completed"}}, str(p, "stage"), null), 3));
        t.addCell(labelCell("Progress during the year"));
        t.addCell(spanned(new Phrase(pdf(str(p, "progress")), BODY), 3));
        place(doc, t, 70);
    }

    private void funded(Document doc) throws DocumentException {
        push(heading("6. Funded Projects / Consultancy Summary:"));
        Map<String, Number> s = summary("funded-projects");
        push(line("Sanctioned: " + dash(number(s.get("sanctioned"))) + "        Applied: " + dash(number(s.get("applied")))
                + "        Total Amount Sanctioned (Rs.): " + dash(money(s.get("totalAmountSanctioned")))
                + "        Consultancy Revenue (Rs.): " + dash(money(s.get("consultancyRevenue")))));
        grid(doc, List.of(
                new Col("Title", 18, r -> str(r, "title"), false),
                new Col("Role (PI / Co-PI) & Team", 12, r -> label(r.get("role")) + (str(r, "team").isEmpty() ? "" : ", " + str(r, "team")), false),
                new Col("Type (Research / Consultancy)", 10, r -> label(r.get("type")), false),
                new Col("Funding Agency / Client", 12, r -> str(r, "fundingAgencyClient"), false),
                new Col("Amount (Rs.)", 9, r -> money(r.get("amount")), true),
                new Col("Duration (From–To)", 13, r -> range(r), false),
                new Col("Status (Sanctioned / Applied)", 10, r -> label(r.get("status")), false),
                new Col("Year", 5, r -> str(r, "year"), true)),
                rows("funded-projects"));
    }

    private void ipr(Document doc) throws DocumentException {
        push(heading("7. Patents, Books & IPR:"));
        push(sub("(a) Patents / Designs / Copyrights"));
        Map<String, Number> p = summary("patents-ipr");
        push(line("Filed: " + dash(number(p.get("filed"))) + "        Published: " + dash(number(p.get("published")))
                + "        Granted: " + dash(number(p.get("granted")))));
        grid(doc, List.of(
                new Col("Applicant / Inventor(s)", 16, r -> str(r, "applicantInventors"), false),
                new Col("Title & Application / Patent No.", 24,
                        r -> str(r, "title") + (str(r, "applicationPatentNo").isEmpty() ? "" : " – " + str(r, "applicationPatentNo")), false),
                new Col("Type (Design / Utility / Copyright)", 12, r -> label(r.get("type")), false),
                new Col("Status (Filed / Published / Granted)", 12, r -> label(r.get("status")), false),
                new Col("Date (DD-MM-YYYY)", 11, r -> date(r.get("recordDate")), false)),
                rows("patents-ipr"));
        push(sub("(b) Books / Book chapters"));
        Map<String, Number> b = summary("books");
        push(line("No. of Books Published: " + dash(number(b.get("books"))) + "        No. of Book Chapters Published: " + dash(number(b.get("chapters")))));
        grid(doc, List.of(
                new Col("Authors", 16, r -> str(r, "authors"), false),
                new Col("Title of the Book / Chapter", 24, r -> str(r, "title"), false),
                new Col("Publisher", 14, r -> str(r, "publisher"), false),
                new Col("ISBN", 11, r -> str(r, "isbn"), false),
                new Col("Month & Year", 9, r -> monthYear(r.get("monthYear")), false),
                new Col("Type (Book / Chapter)", 8, r -> label(r.get("type")), false)),
                rows("books"));
    }

    private void outreach(Document doc) throws DocumentException {
        push(heading("8. Outreach:"));
        grid(doc, List.of(
                new Col("Role*", 16, r -> label(r.get("role")), false),
                new Col("Event / Activity", 24, r -> str(r, "eventActivity"), false),
                new Col("Name of the Organization / Institution", 20, r -> str(r, "organization"), false),
                new Col("Venue", 12, r -> str(r, "venue"), false),
                new Col("Date", 10, r -> date(r.get("eventDate")), false)),
                rows("outreach"));
        Paragraph foot = new Paragraph();
        foot.add(new Chunk("* Role may be: ", FontFactory.getFont(FontFactory.TIMES_BOLDITALIC, 7.5f)));
        foot.add(new Chunk("Conference Session Chair / Expert Lectures Delivered / Resource Person / Editorial Board Member / "
                + "Reviewer for Journals / External Examiner / External Thesis Evaluated / Visiting Researcher / "
                + "Industry Interaction or MoU / International Conferences Attended / Others.", FontFactory.getFont(FontFactory.TIMES_ITALIC, 7.5f)));
        foot.setSpacingBefore(2);
        doc.add(foot);
    }

    private void awards(Document doc) throws DocumentException {
        push(heading("9. Professional Memberships, Awards & Recognitions:"));
        grid(doc, List.of(
                new Col("Membership / Award / Recognition", 28, r -> str(r, "item"), false),
                new Col("Professional / Awarding Body (IEEE, CSI, ISTE, IE(I), etc.)", 26, r -> str(r, "awardingBody"), false),
                new Col("Level (Inst / State / Nat / Intl)", 14, r -> label(r.get("level")), false),
                new Col("Year", 6, r -> str(r, "year"), true)),
                rows("memberships-awards"));
    }

    private void other(Document doc) throws DocumentException {
        push(heading("10. Any Other Contributions:"));
        Map<String, Object> o = first("other-contributions");
        push(sub("At Department level:"));
        place(doc, textBox(str(o, "departmentContribution"), 44), 56);
        push(sub("At Institute level:"));
        place(doc, textBox(str(o, "instituteContribution"), 44), 56);
    }

    private void scoreSheet(Document doc) throws DocumentException {
        push(heading("11. Self Appraisal Score Sheet:"));
        push(note("Maximum marks for your cadre are given in Annexure A, and the scoring components they are made of in Annexure B."));
        PdfPTable t = new PdfPTable(new float[] {5, 50, 14, 14});
        t.setWidthPercentage(100);
        t.setHeaderRows(1);
        t.addCell(th("S. No"));
        t.addCell(th("Criteria"));
        t.addCell(th("Max. Marks (my cadre)"));
        t.addCell(th("Self-Score"));
        int i = 1, max = 0;
        double self = 0;
        boolean any = false;
        for (ScoreRow r : d.view().scores()) {
            t.addCell(td(String.valueOf(i++), true, true));
            PdfPCell criterion = td(r.label(), false, false);
            if (!r.components().isEmpty() || r.maxMarks() == null) {
                Phrase withComponents = new Phrase(pdf(r.label()), BODY);
                if (!r.components().isEmpty()) {
                    withComponents.add(new Chunk(pdf("\n" + components(r.components())), ITALIC));
                }
                if (r.maxMarks() == null) {
                    String lines = r.breakdown().stream().filter(l -> l.count() > 0)
                            .map(l -> l.description() + ": " + l.count() + " x " + ScoringRules.plain(l.perEntry()) + " = " + ScoringRules.plain(l.marks()))
                            .collect(Collectors.joining("; "));
                    withComponents.add(new Chunk(pdf("\n" + (lines.isEmpty() ? "No entries." : lines + ".")), ITALIC));
                }
                criterion.setPhrase(withComponents);
            }
            t.addCell(criterion);
            t.addCell(td(r.maxMarks() == null ? "Per entry" : String.valueOf(r.maxMarks()), true, false));
            t.addCell(td(r.score() == null ? "" : number(r.score()) + (r.selfScore() != null && r.calculated() != null ? "*" : ""), true, false));
            if (r.maxMarks() != null) max += r.maxMarks();
            if (r.score() != null) {
                self += r.score().doubleValue();
                any = true;
            }
        }
        PdfPCell total = td("Total", false, false);
        total.setColspan(2);
        total.setHorizontalAlignment(Element.ALIGN_CENTER);
        total.setBackgroundColor(PALE);
        total.setPhrase(new Phrase("Total", BODY_BOLD));
        t.addCell(total);
        PdfPCell m = td(max + " + per entry", true, false);
        m.setBackgroundColor(PALE);
        m.setPhrase(new Phrase(max + " + per entry", BODY_BOLD));
        t.addCell(m);
        PdfPCell sc = td(any ? number(BigDecimal.valueOf(self)) : "", true, false);
        sc.setBackgroundColor(PALE);
        sc.setPhrase(new Phrase(any ? number(BigDecimal.valueOf(self)) : "", BODY_BOLD));
        t.addCell(sc);
        t.setSpacingBefore(3);
        place(doc, t, 90);
    }

    // ====================================================================================================
    // Declaration, HoD and Principal
    // ====================================================================================================

    private void declarationAndReview(Document doc) throws DocumentException {
        Paragraph decl = new Paragraph("Declaration", BODY_BOLD);
        decl.setSpacingBefore(10);
        decl.setKeepTogether(true);
        doc.add(decl);
        Paragraph text = new Paragraph("I hereby declare that the information furnished in this report is true and correct to the best of my "
                + "knowledge, and that supporting documents are available for verification.", BODY);
        text.setSpacingBefore(2);
        doc.add(text);

        AppraisalView v = d.view();
        PdfPTable sig = new PdfPTable(new float[] {3, 3});
        sig.setWidthPercentage(100);
        sig.setSpacingBefore(14);
        sig.setKeepTogether(true);
        String when = v.declaredAt() == null ? "" : v.declaredAt().atZoneSameInstant(ZoneId.systemDefault()).format(DAY);
        sig.addCell(plain(new Phrase(pdf("Date: " + (when.isEmpty() ? "" : when)), BODY), Element.ALIGN_LEFT));
        sig.addCell(plain(new Phrase("Signature of Faculty", BODY_BOLD), Element.ALIGN_RIGHT));
        doc.add(sig);

        reviewBox(doc, "Recommendations of HoD", d.hodRecommendation(), d.hodAt(), "Head of the Department", "recommendation");
        // The Dean and Vice Principal levels were withdrawn. What they recorded on an appraisal that passed them stays on its report.
        if (d.deanAt() != null) reviewBox(doc, "Remarks of the Dean", d.deanRemarks(), d.deanAt(), "Dean", "remarks");
        if (d.vpAt() != null) reviewBox(doc, "Remarks of the Vice Principal", d.vpRemarks(), d.vpAt(), "Vice Principal", "remarks");
        reviewBox(doc, "Remarks of the Principal", d.principalRemarks(), d.principalAt(), "Principal", "remarks");
        // The Director Technical decides at the Principal's level, so the form carries a box for each. Whichever of them did not
        // act on this appraisal has an empty box to sign on paper.
        reviewBox(doc, "Remarks of the Director Technical", d.directorRemarks(), d.directorAt(), "Director Technical", "remarks");
    }

    private void reviewBox(Document doc, String title, String text, OffsetDateTime at, String who, String what) throws DocumentException {
        PdfPTable block = new PdfPTable(1);
        block.setWidthPercentage(100);
        block.setSpacingBefore(12);
        block.setKeepTogether(true);

        PdfPCell h = new PdfPCell(new Phrase(title, HEADING));
        h.setBorder(Rectangle.BOTTOM);
        h.setBorderColor(NAVY);
        h.setBorderWidthBottom(0.8f);
        h.setPaddingLeft(0);
        h.setPaddingBottom(2);
        block.addCell(h);

        Phrase body = new Phrase();
        if (text != null && !text.isBlank()) body.add(new Phrase(pdf(text), BODY));
        if (at != null) {
            body.add(new Phrase(pdf("\n\nRecorded in the appraisal system on " + at.atZoneSameInstant(ZoneId.systemDefault()).format(DAY)
                    + (text == null || text.isBlank() ? " (no written " + what + ")." : ".")), ITALIC));
        }
        PdfPCell box = new PdfPCell(body);
        box.setBorderColor(GREY);
        box.setBorderWidth(0.5f);
        box.setMinimumHeight(60);
        box.setPadding(5);
        block.addCell(box);

        Paragraph sign = new Paragraph();
        sign.setAlignment(Element.ALIGN_RIGHT);
        sign.add(new Chunk(who, BODY_BOLD));
        sign.add(new Chunk(" (Signature with Seal & Date)", BODY));
        PdfPCell signCell = new PdfPCell(sign);
        signCell.setBorder(Rectangle.NO_BORDER);
        signCell.setPaddingTop(14);
        signCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        block.addCell(signCell);
        doc.add(block);
    }

    // ====================================================================================================
    // Annexure A
    // ====================================================================================================

    private void annexureA(Document doc) throws DocumentException {
        doc.newPage();
        doc.add(bar("ANNEXURE A – CADRE-WISE MAXIMUM MARKS", 0));

        List<Cadre> cadres = d.annexure();
        float[] widths = new float[2 + cadres.size()];
        widths[0] = 5;
        widths[1] = 40;
        for (int i = 0; i < cadres.size(); i++) widths[2 + i] = 11;
        PdfPTable t = new PdfPTable(widths);
        t.setWidthPercentage(100);
        t.setHeaderRows(1);
        t.addCell(peachHeader("S. No"));
        t.addCell(peachHeader("Criteria"));
        for (Cadre c : cadres) t.addCell(peachHeader(shortCadre(c)));

        int n = 1;
        Map<String, Integer> totals = new LinkedHashMap<>();
        for (Criteria crit : Criteria.values()) {
            t.addCell(td(String.valueOf(n++), true, true));
            t.addCell(td(crit.label(), false, false));
            for (Cadre c : cadres) {
                Integer v = c.marks().get(crit.name());
                if (crit.perEntry()) {
                    t.addCell(td("Per entry", true, true));
                    continue;
                }
                t.addCell(td(v == null ? "" : String.valueOf(v), true, true));
                if (v != null) totals.merge(c.code(), v, Integer::sum);
            }
        }
        PdfPCell label = new PdfPCell(new Phrase("Total of B1 to B4 (B5 to B9 are marked per entry)", BODY_BOLD));
        label.setColspan(2);
        label.setHorizontalAlignment(Element.ALIGN_CENTER);
        label.setBackgroundColor(PEACH);
        label.setPadding(3);
        label.setBorderColor(GREY);
        label.setBorderWidth(0.5f);
        t.addCell(label);
        for (Cadre c : cadres) {
            PdfPCell tc = new PdfPCell(new Phrase(String.valueOf(totals.getOrDefault(c.code(), 0)), BODY_BOLD));
            tc.setBackgroundColor(PEACH);
            tc.setHorizontalAlignment(Element.ALIGN_CENTER);
            tc.setPadding(3);
            tc.setBorderColor(GREY);
            tc.setBorderWidth(0.5f);
            t.addCell(tc);
        }
        t.setSpacingBefore(2);
        doc.add(t);
        Paragraph foot = new Paragraph("The same criteria and maximum marks are used by the Appraisal Panel in the Faculty Performance Evaluation Form.",
                FontFactory.getFont(FontFactory.TIMES_ITALIC, 8f));
        foot.setSpacingBefore(3);
        doc.add(foot);
    }

    /**
     * Annexure B: for the faculty member's own cadre, each criterion's maximum and the components it is made of, in the
     * wording of the college's cadre-wise scoring document. Criteria that document does not break down are left out.
     */
    private void annexureB(Document doc) throws DocumentException {
        List<ScoreRow> detailed = d.view().scores().stream().filter(r -> !r.components().isEmpty()).toList();
        if (detailed.isEmpty()) return;
        doc.add(bar("ANNEXURE B – SCORING COMPONENTS", 14));
        doc.add(note("Cadre: " + d.view().cadre()));

        PdfPTable t = new PdfPTable(new float[] {30, 10, 60});
        t.setWidthPercentage(100);
        t.setHeaderRows(1);
        t.addCell(peachHeader("Criterion"));
        t.addCell(peachHeader("Maximum Marks"));
        t.addCell(peachHeader("Scoring Components"));
        for (ScoreRow r : detailed) {
            t.addCell(td(r.reference() + ". " + r.label(), false, false));
            t.addCell(td(String.valueOf(r.maxMarks()), true, true));
            t.addCell(td(components(r.components()), false, false));
        }
        t.setSpacingBefore(2);
        doc.add(t);
    }

    /** "Workload & course delivery – 10; pass/result performance – 10; ... ." as the source document writes it. */
    private static String components(List<Component> components) {
        return components.stream().map(c -> c.description() + " – " + c.maxMarks()).collect(Collectors.joining("; ")) + ".";
    }

    private static String shortCadre(Cadre c) {
        return switch (c.code()) {
            case "LECTURER" -> "Lecturer";
            case "ASST_PROF" -> "Asst. Prof.";
            case "SR_ASST_PROF" -> "Sr. Asst. Prof.";
            case "ASSOC_PROF" -> "Assoc. Prof.";
            case "PROFESSOR" -> "Professor";
            default -> c.name();
        };
    }

    // ====================================================================================================
    // Keeping headings with their tables
    // ====================================================================================================

    private void push(Paragraph p) {
        float size = p.getFont() == null ? 9 : p.getFont().getSize();
        pending.add(new Pending(p, size * 1.7f + p.getSpacingBefore() + p.getSpacingAfter()));
    }

    private void flush(Document doc) throws DocumentException {
        for (Pending p : pending) doc.add(p.paragraph());
        pending.clear();
    }

    /**
     * Adds a table preceded by any waiting headings. If the headings plus the table's header and first rows
     * ({@code minForTable} points) do not fit on the current page, everything moves to the next page together,
     * so a heading is never stranded at the bottom of a page.
     */
    private void place(Document doc, PdfPTable table, float minForTable) throws DocumentException {
        float need = minForTable;
        for (Pending p : pending) need += p.height();
        if (writer.getVerticalPosition(true) - doc.bottom() < need) doc.newPage();
        // The space a paragraph asks for after itself is not honoured before a table, so the table carries it.
        if (!pending.isEmpty()) table.setSpacingBefore(Math.max(table.spacingBefore(), 4.5f));
        flush(doc);
        doc.add(table);
    }

    // ====================================================================================================
    // Building blocks
    // ====================================================================================================

    private Paragraph centered(String text, Font f) {
        Paragraph p = new Paragraph(pdf(text), f);
        p.setAlignment(Element.ALIGN_CENTER);
        p.setLeading(f.getSize() * 1.25f);
        return p;
    }

    private PdfPTable bar(String text, float spaceBefore) {
        PdfPTable t = new PdfPTable(1);
        t.setWidthPercentage(100);
        t.setSpacingBefore(spaceBefore);
        PdfPCell c = new PdfPCell(new Phrase(text, BAR));
        c.setBackgroundColor(NAVY);
        c.setBorder(Rectangle.NO_BORDER);
        c.setPadding(3);
        c.setPaddingLeft(5);
        t.addCell(c);
        t.setKeepTogether(true);
        return t;
    }

    private Paragraph heading(String text) {
        Paragraph p = new Paragraph(text, HEADING);
        p.setSpacingBefore(8);
        p.setSpacingAfter(3);
        p.setKeepTogether(true);
        return p;
    }

    private Paragraph sub(String text) {
        Paragraph p = new Paragraph(pdf(text), SUB);
        p.setSpacingBefore(5);
        p.setSpacingAfter(3.5f);
        p.setKeepTogether(true);
        return p;
    }

    private Paragraph note(String text) {
        Paragraph p = new Paragraph(pdf(text), FontFactory.getFont(FontFactory.TIMES_ITALIC, 7.5f));
        p.setSpacingAfter(3f);
        return p;
    }

    private Paragraph line(String text) {
        Paragraph p = new Paragraph(pdf(text), BODY_BOLD);
        p.setSpacingBefore(2.5f);
        p.setSpacingAfter(3f);
        return p;
    }

    private PdfPCell labelCell(String text) {
        PdfPCell c = new PdfPCell(new Phrase(pdf(text), BODY_BOLD));
        c.setBackgroundColor(PALE);
        c.setBorderColor(GREY);
        c.setBorderWidth(0.5f);
        c.setPadding(4);
        c.setVerticalAlignment(Element.ALIGN_MIDDLE);
        return c;
    }

    private PdfPCell valueCell(Phrase p) {
        PdfPCell c = new PdfPCell(p);
        c.setBorderColor(GREY);
        c.setBorderWidth(0.5f);
        c.setPadding(4);
        c.setVerticalAlignment(Element.ALIGN_MIDDLE);
        c.setMinimumHeight(18);
        return c;
    }

    private PdfPCell spanned(Phrase p, int cols) {
        PdfPCell c = valueCell(p);
        c.setColspan(cols);
        return c;
    }

    private void pair(PdfPTable t, String label, String value) {
        t.addCell(labelCell(label));
        t.addCell(valueCell(new Phrase(pdf(value == null ? "" : value), BODY)));
    }

    private PdfPCell plain(Phrase p, int align) {
        PdfPCell c = new PdfPCell(p);
        c.setBorder(Rectangle.NO_BORDER);
        c.setHorizontalAlignment(align);
        return c;
    }

    private PdfPCell th(String text) {
        PdfPCell c = new PdfPCell(new Phrase(pdf(text), TH));
        c.setBackgroundColor(HEADER_BLUE);
        c.setHorizontalAlignment(Element.ALIGN_CENTER);
        c.setVerticalAlignment(Element.ALIGN_MIDDLE);
        c.setBorderColor(GREY);
        c.setBorderWidth(0.5f);
        c.setPadding(3);
        return c;
    }

    private PdfPCell peachHeader(String text) {
        PdfPCell c = th(text);
        c.setBackgroundColor(PEACH);
        return c;
    }

    private PdfPCell td(String text, boolean right, boolean center) {
        PdfPCell c = new PdfPCell(new Phrase(pdf(text), BODY));
        c.setBorderColor(GREY);
        c.setBorderWidth(0.5f);
        c.setPadding(3);
        c.setVerticalAlignment(Element.ALIGN_TOP);
        c.setHorizontalAlignment(center ? Element.ALIGN_CENTER : right ? Element.ALIGN_RIGHT : Element.ALIGN_LEFT);
        return c;
    }

    private PdfPTable textBox(String text, float minHeight) {
        PdfPTable t = new PdfPTable(1);
        t.setWidthPercentage(100);
        PdfPCell c = new PdfPCell(new Phrase(pdf(text), BODY));
        c.setBorderColor(GREY);
        c.setBorderWidth(0.5f);
        c.setPadding(5);
        c.setMinimumHeight(minHeight);
        t.addCell(c);
        return t;
    }

    private void grid(Document doc, List<Col> cols, List<Map<String, Object>> data) throws DocumentException {
        grid(doc, cols, data, true);
    }

    /** The form's numbered table: S. No plus the given columns; an empty list prints one "None entered" row. */
    private void grid(Document doc, List<Col> cols, List<Map<String, Object>> data, boolean numbered) throws DocumentException {
        float[] w = new float[cols.size() + (numbered ? 1 : 0)];
        int k = 0;
        if (numbered) w[k++] = 4.5f;
        for (Col c : cols) w[k++] = c.weight();
        PdfPTable t = new PdfPTable(w);
        t.setWidthPercentage(100);
        t.setHeaderRows(1);
        t.setSplitLate(false);
        if (numbered) t.addCell(th("S. No"));
        for (Col c : cols) t.addCell(th(c.header()));
        if (data.isEmpty()) {
            PdfPCell none = new PdfPCell(new Phrase("None entered", ITALIC));
            none.setColspan(w.length);
            none.setBorderColor(GREY);
            none.setBorderWidth(0.5f);
            none.setPadding(4);
            t.addCell(none);
        } else {
            int i = 1;
            for (Map<String, Object> r : data) {
                if (numbered) t.addCell(td(String.valueOf(i++), true, true));
                for (Col c : cols) t.addCell(td(c.value().apply(r), c.right(), false));
            }
        }
        place(doc, t, 56);
    }

    /** "[x] CSE / IT  [ ] CE ..." with the selected option filled; falls back to the plain name when nothing matches. */
    private Phrase checkboxes(String[][] options, String selected, String fallbackName) {
        Phrase p = new Phrase();
        boolean matched = false;
        for (String[] o : options) {
            boolean on = o[0].equals(selected);
            matched |= on;
            p.add(new Chunk(on ? "n" : "o", BOX));
            p.add(new Phrase(pdf(" " + o[1] + "    "), on ? BODY_BOLD : BODY));
        }
        if (!matched && fallbackName != null && !fallbackName.isBlank()) {
            p.add(new Phrase(pdf("(" + fallbackName + ")"), ITALIC));
        }
        return p;
    }

    // ---- data access ----

    private List<Map<String, Object>> rows(String key) {
        SectionView v = d.sections().get(key);
        return v == null ? List.of() : v.records();
    }

    private List<Map<String, Object>> rowsWhere(String key, String field, String value) {
        return rows(key).stream().filter(r -> value.equals(r.get(field))).collect(Collectors.toList());
    }

    private Map<String, Object> first(String key) {
        List<Map<String, Object>> r = rows(key);
        return r.isEmpty() ? Map.of() : r.get(0);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Number> summary(String key) {
        SectionView v = d.sections().get(key);
        return v == null || v.summary() == null ? Map.of() : (Map<String, Number>) (Map<String, ?>) v.summary();
    }

    private static String str(Map<String, Object> r, String f) {
        if (r == null) return "";
        Object v = r.get(f);
        return v == null ? "" : number(v);
    }

    private static String range(Map<String, Object> r) {
        String a = date(r.get("startDate"));
        String b = date(r.get("endDate"));
        return a.isEmpty() || b.isEmpty() ? a + b : a + " to " + b;
    }

    private static String dash(String s) { return s == null || s.isEmpty() ? "—" : s; }

    private static String blank(String s) { return s == null || s.isEmpty() ? "____" : s; }

    // ====================================================================================================
    // Header, footer and the draft watermark
    // ====================================================================================================

    private final class PageDecor extends PdfPageEventHelper {
        private final Font stampFont = FontFactory.getFont(FontFactory.HELVETICA, 7f, Font.NORMAL, GREY);
        private PdfTemplate total;
        private BaseFont base;

        @Override
        public void onOpenDocument(PdfWriter writer, Document document) {
            total = writer.getDirectContent().createTemplate(30, 12);
            try {
                base = BaseFont.createFont(BaseFont.HELVETICA, BaseFont.WINANSI, false);
            } catch (DocumentException | IOException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            PdfContentByte cb = writer.getDirectContent();
            float left = document.left(), right = document.right();

            // Header on pages after the first: the form's running head.
            if (writer.getPageNumber() > 1) {
                cb.beginText();
                cb.setFontAndSize(BaseFontHolder.bold(), 8.5f);
                cb.setColorFill(BLUE);
                cb.showTextAligned(Element.ALIGN_LEFT, "SRI VASAVI ENGINEERING COLLEGE (AUTONOMOUS)", left, document.top() + 30, 0);
                cb.setFontAndSize(base, 8);
                cb.setColorFill(new Color(0x40, 0x40, 0x40));
                cb.showTextAligned(Element.ALIGN_RIGHT, "Faculty Self Appraisal & Assessment Report", right, document.top() + 30, 0);
                cb.endText();
                cb.setColorStroke(NAVY);
                cb.setLineWidth(0.8f);
                cb.moveTo(left, document.top() + 25);
                cb.lineTo(right, document.top() + 25);
                cb.stroke();
            }

            // Footer: generation stamp left, "Page x of y" right.
            String stamp = "Appraisal no. " + d.view().id() + " · " + d.view().employeeId() + " · generated "
                    + d.generatedAt().atZoneSameInstant(ZoneId.systemDefault()).format(STAMP)
                    + (d.approved() ? "" : " · DRAFT");
            ColumnText.showTextAligned(cb, Element.ALIGN_LEFT, new Phrase(pdf(stamp), stampFont), left, document.bottom() - 20, 0);
            String pageText = "Page " + writer.getPageNumber() + " of ";
            float len = base.getWidthPoint(pageText, 8);
            cb.beginText();
            cb.setFontAndSize(base, 8);
            cb.setColorFill(Color.BLACK);
            cb.showTextAligned(Element.ALIGN_LEFT, pageText, right - len - 12, document.bottom() - 20, 0);
            cb.endText();
            cb.addTemplate(total, right - 12, document.bottom() - 20);

            if (!d.approved()) {
                PdfContentByte under = writer.getDirectContentUnder();
                under.saveState();
                PdfGState g = new PdfGState();
                g.setFillOpacity(0.07f);
                under.setGState(g);
                under.beginText();
                under.setFontAndSize(BaseFontHolder.bold(), 120);
                under.setColorFill(new Color(0xB3, 0x26, 0x1E));
                under.showTextAligned(Element.ALIGN_CENTER, "DRAFT", PageSize.A4.getWidth() / 2, PageSize.A4.getHeight() / 2 - 40, 45);
                under.endText();
                under.restoreState();
            }
        }

        @Override
        public void onCloseDocument(PdfWriter writer, Document document) {
            total.beginText();
            total.setFontAndSize(base, 8);
            total.setColorFill(Color.BLACK);
            total.setTextMatrix(0, 0);
            total.showText(String.valueOf(writer.getPageNumber() - 1));
            total.endText();
        }
    }

    /** One shared bold font for the page decoration. */
    private static final class BaseFontHolder {
        private static BaseFont bold;

        static synchronized BaseFont bold() {
            if (bold == null) {
                try {
                    bold = BaseFont.createFont(BaseFont.HELVETICA_BOLD, BaseFont.WINANSI, false);
                } catch (DocumentException | IOException e) {
                    throw new IllegalStateException(e);
                }
            }
            return bold;
        }
    }
}
