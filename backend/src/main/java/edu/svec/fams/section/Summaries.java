package edu.svec.fams.section;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The counts and amounts the official form prints above some tables ("SCI/SCIE: __  Scopus: __ ...",
 * "Sanctioned: __  Applied: __ ...", "Filed: __ Published: __ Granted: __", "No. of Books Published: __").
 * They are plain tallies of the saved rows, computed on read and never stored, so they cannot disagree with
 * the table. Where the form leaves the exact meaning open it is noted and listed in docs/requirements.md.
 */
final class Summaries {
    private Summaries() {}

    /** Journal publications by indexing. */
    static Map<String, Object> journals(List<Map<String, Object>> rows) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sciScie", count(rows, "indexing", "SCI_SCIE"));
        m.put("scopus", count(rows, "indexing", "SCOPUS"));
        m.put("ugcCareAbdc", count(rows, "indexing", "UGC_CARE_ABDC"));
        m.put("others", count(rows, "indexing", "OTHERS"));
        m.put("total", rows.size());
        return m;
    }

    /**
     * "Sanctioned / Applied" count every project by status. "Total Amount Sanctioned" is the sum for sanctioned
     * research projects and "Consultancy Revenue" the sum for sanctioned consultancy: an assumption, because the
     * form does not define either figure.
     */
    static Map<String, Object> fundedProjects(List<Map<String, Object>> rows) {
        BigDecimal research = BigDecimal.ZERO;
        BigDecimal consultancy = BigDecimal.ZERO;
        for (Map<String, Object> r : rows) {
            if (!"SANCTIONED".equals(r.get("status"))) continue;
            BigDecimal amount = (BigDecimal) r.get("amount");
            if ("RESEARCH".equals(r.get("type"))) research = research.add(amount);
            if ("CONSULTANCY".equals(r.get("type"))) consultancy = consultancy.add(amount);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sanctioned", count(rows, "status", "SANCTIONED"));
        m.put("applied", count(rows, "status", "APPLIED"));
        m.put("totalAmountSanctioned", research.setScale(2));
        m.put("consultancyRevenue", consultancy.setScale(2));
        return m;
    }

    /** Patents, designs and copyrights by status. */
    static Map<String, Object> patents(List<Map<String, Object>> rows) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("filed", count(rows, "status", "FILED"));
        m.put("published", count(rows, "status", "PUBLISHED"));
        m.put("granted", count(rows, "status", "GRANTED"));
        return m;
    }

    /** "No. of Books Published" and "No. of Book Chapters Published". */
    static Map<String, Object> books(List<Map<String, Object>> rows) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("books", count(rows, "type", "BOOK"));
        m.put("chapters", count(rows, "type", "CHAPTER"));
        return m;
    }

    private static int count(List<Map<String, Object>> rows, String field, String value) {
        int n = 0;
        for (Map<String, Object> r : rows) {
            if (value.equals(r.get(field))) n++;
        }
        return n;
    }
}
