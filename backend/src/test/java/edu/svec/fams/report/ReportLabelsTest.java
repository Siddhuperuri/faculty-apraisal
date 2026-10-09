package edu.svec.fams.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.svec.fams.section.FieldSpec;
import edu.svec.fams.section.SectionSpec;
import edu.svec.fams.section.Sections;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ReportLabelsTest {

    @Test
    void everyChoiceValueOfEverySectionHasAnOfficialLabel() {
        // The printed form shows words ("Co-PI", "SCI / SCIE"), never codes.
        for (SectionSpec s : Sections.all().values()) {
            for (FieldSpec f : s.fields()) {
                if (f.type() != FieldSpec.Type.ENUM) continue;
                for (String v : f.allowed()) {
                    assertTrue(ReportLabels.hasLabel(v), s.key() + "." + f.name() + " = " + v + " has no label");
                    assertFalse(ReportLabels.label(v).contains("_"), v);
                }
            }
        }
    }

    @Test
    void formatsMatchTheForm() {
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
