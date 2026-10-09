package edu.svec.fams.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Text for the printed form: the official wording for stored codes, DD-MM-YYYY dates, Indian digit grouping, and a
 * safety net for characters the standard PDF fonts cannot draw (they become "?" instead of silently vanishing).
 */
final class ReportLabels {
    private ReportLabels() {}

    private static final Map<String, String> LABELS = new HashMap<>();

    static {
        String[][] pairs = {
                {"INST", "Inst"}, {"STATE", "State"}, {"NAT", "Nat"}, {"INTL", "Intl"},
                {"THEORY", "Theory"}, {"LAB", "Lab"}, {"B_TECH", "B.Tech"}, {"PHARMACY", "Pharmacy"}, {"DIPLOMA", "Diploma"},
                {"CSE", "CSE"}, {"AIML", "AI & ML"}, {"ECE", "ECE"}, {"EEE", "EEE"}, {"ME", "ME"}, {"CE", "CE"}, {"BSH", "BSH"},
                {"PHARMACEUTICS", "Pharmaceutics"}, {"PHARMACEUTICAL_CHEMISTRY", "Pharmaceutical Chemistry"},
                {"1", "1"}, {"2", "2"}, {"3", "3"}, {"4", "4"}, {"5", "5"}, {"6", "6"}, {"7", "7"}, {"8", "8"},
                {"PHARMACOLOGY", "Pharmacology"}, {"PHARMACOGNOSY", "Pharmacognosy"}, {"PHARMACY_PRACTICE", "Pharmacy Practice"}, {"UG", "UG"}, {"PG", "PG"},
                {"PAPER", "Paper"}, {"PATENT", "Patent"}, {"PROTOTYPE", "Prototype"}, {"COMPETITION", "Competition"}, {"NONE", "None"},
                {"OFFLINE", "Offline"}, {"ONLINE", "Online"}, {"BLENDED", "Blended"},
                {"NPTEL", "NPTEL"}, {"SWAYAM", "Swayam"}, {"COURSERA", "Coursera"}, {"OTHER", "Other"},
                {"INSTITUTE", "Institute level"}, {"DEPARTMENT", "Department level"},
                {"SCI_SCIE", "SCI / SCIE"}, {"SCOPUS", "Scopus"}, {"UGC_CARE_ABDC", "UGC-CARE / ABDC"}, {"OTHERS", "Others"},
                {"GOOGLE_SCHOLAR", "Google Scholar"}, {"WEB_OF_SCIENCE", "Web of Science"},
                {"PHD", "Ph.D."}, {"MTECH", "M.Tech"}, {"MBA", "MBA"},
                {"REGISTERED", "Registered"}, {"SUBMITTED", "Submitted"}, {"AWARDED", "Awarded"},
                {"PURSUING", "Pursuing"}, {"NOT_APPLICABLE", "Not applicable"},
                {"COURSE_WORK", "Course work"}, {"COMPREHENSIVE_PROPOSAL", "Comprehensive / Proposal"},
                {"SYNOPSIS", "Synopsis / Pre-Ph.D."}, {"THESIS_SUBMITTED", "Thesis submitted"},
                {"VIVA_COMPLETED", "Viva-voce completed"},
                {"PI", "PI"}, {"CO_PI", "Co-PI"}, {"RESEARCH", "Research"}, {"CONSULTANCY", "Consultancy"},
                {"SANCTIONED", "Sanctioned"}, {"APPLIED", "Applied"},
                {"DESIGN", "Design"}, {"UTILITY", "Utility"}, {"COPYRIGHT", "Copyright"},
                {"FILED", "Filed"}, {"PUBLISHED", "Published"}, {"GRANTED", "Granted"},
                {"BOOK", "Book"}, {"CHAPTER", "Chapter"},
                {"CONFERENCE_SESSION_CHAIR", "Conference Session Chair"},
                {"EXPERT_LECTURE_DELIVERED", "Expert Lectures Delivered"},
                {"RESOURCE_PERSON", "Resource Person"}, {"EDITORIAL_BOARD_MEMBER", "Editorial Board Member"},
                {"JOURNAL_REVIEWER", "Reviewer for Journals"}, {"EXTERNAL_EXAMINER", "External Examiner"},
                {"EXTERNAL_THESIS_EVALUATED", "External Thesis Evaluated"},
                {"VISITING_RESEARCHER", "Visiting Researcher"},
                {"INDUSTRY_INTERACTION_MOU", "Industry Interaction or MoU"},
                {"INTERNATIONAL_CONFERENCE_ATTENDED", "International Conferences Attended"},
        };
        for (String[] p : pairs) LABELS.put(p[0], p[1]);
    }

    private static final String[] MONTHS = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};
    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    private static final CharsetEncoder WIN_ANSI = Charset.forName("windows-1252").newEncoder();

    static String label(Object code) {
        if (code == null) return "";
        String c = code.toString();
        String known = LABELS.get(c);
        if (known != null) return known;
        String t = c.toLowerCase(Locale.ROOT).replace('_', ' ');
        return t.isEmpty() ? t : Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    static boolean hasLabel(String code) { return LABELS.containsKey(code); }

    /** The status names used on screen, for the draft banner. */
    static String statusLabel(String status) {
        return switch (status) {
            case "DRAFT" -> "Draft";
            case "SUBMITTED" -> "Submitted";
            case "HOD_REVIEW" -> "With HoD";
            case "HOD_APPROVED" -> "HoD recommended";
            case "PRINCIPAL_REVIEW" -> "With Principal / Director";
            case "APPROVED" -> "Approved";
            default -> status;
        };
    }

    static String date(Object o) {
        return o instanceof LocalDate d ? d.format(DMY) : o == null ? "" : o.toString();
    }

    static String monthYear(Object o) {
        if (o == null) return "";
        String v = o.toString();
        if (v.length() == 7 && v.charAt(4) == '-') {
            try {
                return MONTHS[Integer.parseInt(v.substring(5)) - 1] + " " + v.substring(0, 4);
            } catch (RuntimeException e) {
                return v;
            }
        }
        return v;
    }

    /** 92.50 -> "92.5", 4 -> "4". */
    static String number(Object o) {
        if (o == null) return "";
        if (o instanceof BigDecimal b) return b.stripTrailingZeros().toPlainString();
        return o.toString();
    }

    /** Indian digit grouping with two decimals: 250000.5 -> "2,50,000.50". The form prints "(Rs.)" in the heading. */
    static String money(Object o) {
        if (o == null) return "";
        BigDecimal v = (o instanceof BigDecimal b ? b : new BigDecimal(o.toString())).setScale(2, RoundingMode.HALF_UP);
        String plain = v.abs().toPlainString();                 // e.g. 250000.50
        String whole = plain.substring(0, plain.indexOf('.'));
        String frac = plain.substring(plain.indexOf('.'));
        // Lakh grouping: the last three digits, then pairs (2,50,000), written out because the JDK's en-IN pattern groups in threes.
        StringBuilder g = new StringBuilder();
        int n = whole.length();
        if (n <= 3) {
            g.append(whole);
        } else {
            String head = whole.substring(0, n - 3);
            for (int i = 0; i < head.length(); i++) {
                if (i > 0 && (head.length() - i) % 2 == 0) g.append(',');
                g.append(head.charAt(i));
            }
            g.append(',').append(whole, n - 3, n);
        }
        return (v.signum() < 0 ? "-" : "") + g + frac;
    }

    /**
     * Makes text safe for the standard (Latin) PDF fonts. Characters they cannot draw become "?" so a missing glyph is
     * visible rather than silently dropped; control characters (other than newline and tab) are removed.
     */
    static String pdf(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder(raw.length());
        synchronized (WIN_ANSI) {
            raw.codePoints().forEach(cp -> {
                if (cp == '\n' || cp == '\t') {
                    sb.append((char) cp);
                } else if (cp == '\r' || Character.isISOControl(cp)) {
                    return;     // dropped
                } else if (cp <= 0xFFFF && WIN_ANSI.canEncode((char) cp)) {
                    sb.append((char) cp);
                } else {
                    sb.append('?');
                }
            });
        }
        return sb.toString();
    }
}
