package edu.svec.fams.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.svec.fams.TestDb;
import edu.svec.fams.reference.ReferenceData;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class ReportLabelsTest {

    @Autowired JdbcClient jdbc;
    @Autowired TestDb db;
    @Autowired ReferenceData reference;

    @Test
    void everyValueOfEveryListHasTheWordingOfTheForm() {
        db.reset();
        ReportLabels.useLookup(reference::reportLabel);
        // The printed form shows words ("Co-PI", "SCI / SCIE"), never codes.
        List<String> codes = jdbc.sql("""
                SELECT v.code FROM reference_values v JOIN reference_lists l ON l.code = v.list_code
                WHERE l.kind <> 'FREE' AND v.active""").query(String.class).list();
        assertFalse(codes.isEmpty());
        for (String v : codes) {
            assertTrue(ReportLabels.hasLabel(v), v + " has no label");
            assertFalse(ReportLabels.label(v).contains("_"), v);
        }
        assertEquals("Reviewer for Journals", ReportLabels.label("JOURNAL_REVIEWER"));
        assertEquals("Inst", ReportLabels.label("INST"));          // the report's own wording, not the screen's
        assertEquals("Course Coordinator", ReportLabels.label("COORDINATOR"));
    }

    @Test
    void formatsMatchTheForm() {
        ReportLabels.useLookup(reference::reportLabel);
        assertEquals("03-10-2026", ReportLabels.date(LocalDate.of(2026, 10, 3)));
        assertEquals("", ReportLabels.date(null));
        assertEquals("May 2026", ReportLabels.monthYear("2026-05"));
        assertEquals("92.5", ReportLabels.number(new BigDecimal("92.50")));
        assertEquals("4", ReportLabels.number(new BigDecimal("4.0")));
        assertEquals("2,50,000.50", ReportLabels.money(new BigDecimal("250000.5")));
        assertEquals("0.00", ReportLabels.money(BigDecimal.ZERO));
        assertEquals("Reviewer for Journals", ReportLabels.label("JOURNAL_REVIEWER"));
    }

    @Test
    void textTheFontsCannotDrawIsMarkedNotDropped() {
        assertEquals("café O'Neil", ReportLabels.pdf("café O'Neil"));
        assertEquals("a?b", ReportLabels.pdf("a\u0c24b"));
        assertEquals("x?y", ReportLabels.pdf("x\ud83d\ude00y")); // an emoji is one character, shown as one "?"
        assertEquals("line1\nline2", ReportLabels.pdf("line1\nline2"));
        assertEquals("ab", ReportLabels.pdf("a\u0000b"));
        assertEquals("", ReportLabels.pdf(null));
    }
}
