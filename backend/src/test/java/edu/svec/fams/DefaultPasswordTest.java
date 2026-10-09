package edu.svec.fams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.svec.fams.auth.DefaultPassword;
import org.junit.jupiter.api.Test;

class DefaultPasswordTest {

    @Test
    void isTheCollegesStandardPasswordUnlessConfiguredOtherwise() {
        assertEquals("Srivasavi@123", new DefaultPassword("").value());
        assertEquals("Srivasavi@123", new DefaultPassword("   ").value());
        assertEquals("Srivasavi@123", new DefaultPassword(null).value());
        assertEquals("Another#Start9", new DefaultPassword("Another#Start9").value());
    }

    @Test
    void matchesOnlyTheExactPassword() {
        DefaultPassword standard = new DefaultPassword("");
        assertTrue(standard.matches("Srivasavi@123"));
        assertFalse(standard.matches("srivasavi@123"));
        assertFalse(standard.matches("Srivasavi@1234"));
        assertFalse(standard.matches(null));
    }

    @Test
    void aPasswordLongerThanBcryptReadsIsRefusedAtStartUp() {
        assertThrows(IllegalStateException.class, () -> new DefaultPassword("x1".repeat(40)));
    }
}
