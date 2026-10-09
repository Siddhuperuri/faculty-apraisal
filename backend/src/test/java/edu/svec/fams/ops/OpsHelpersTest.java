package edu.svec.fams.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class OpsHelpersTest {

    @Test
    void employeeIdsThatDifferOnlyInCaseSpacesOrPunctuationAreTheSame() {
        assertEquals("EMP001", AccountHygieneService.idKey("emp-001"));
        assertEquals("EMP001", AccountHygieneService.idKey(" EMP 001 "));
        assertNull(AccountHygieneService.idKey("---"));
        assertNull(AccountHygieneService.idKey(null));
    }

    @Test
    void contactNumbersAreComparedByTheirLastTenDigits() {
        assertEquals("9876543210", AccountHygieneService.contactKey("+91 98765 43210"));
        assertEquals("9876543210", AccountHygieneService.contactKey("9876543210"));
        assertNull(AccountHygieneService.contactKey("123"));
        assertNull(AccountHygieneService.contactKey(null));
    }

    @Test
    void aCsvCellIsQuotedWhenNeededAndNeverRunsAsAFormula() {
        assertEquals("plain", ImportHistoryService.cell("plain"));
        assertEquals("\"a,b\"", ImportHistoryService.cell("a,b"));
        assertEquals("\"say \"\"hi\"\"\"", ImportHistoryService.cell("say \"hi\""));
        assertEquals("'=SUM(A1)", ImportHistoryService.cell("=SUM(A1)"));
        assertEquals("'@cmd", ImportHistoryService.cell("@cmd"));
        assertEquals("", ImportHistoryService.cell(null));
    }
}
