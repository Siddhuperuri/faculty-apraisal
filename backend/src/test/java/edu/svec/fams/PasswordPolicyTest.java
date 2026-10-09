package edu.svec.fams;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import edu.svec.fams.auth.PasswordPolicy;
import org.junit.jupiter.api.Test;

class PasswordPolicyTest {

    @Test
    void acceptsAReasonablePassword() {
        assertNull(PasswordPolicy.problem("maple-river-42", "asha@svec.edu"));
        assertNull(PasswordPolicy.problem("a sentence with 1 number", "asha@svec.edu"));
    }

    @Test
    void rejectsTooShortMissingKindsSpacesAndLongOnes() {
        assertNotNull(PasswordPolicy.problem(null, "a@b.co"));
        assertNotNull(PasswordPolicy.problem("", "a@b.co"));
        assertNotNull(PasswordPolicy.problem("short1", "a@b.co"));
        assertNotNull(PasswordPolicy.problem("onlylettersnodigits", "a@b.co"));
        assertNotNull(PasswordPolicy.problem("12345678901234", "a@b.co"));
        assertNotNull(PasswordPolicy.problem(" leading-space-1", "a@b.co"));
        assertNotNull(PasswordPolicy.problem("trailing-space-1 ", "a@b.co"));
        assertNotNull(PasswordPolicy.problem("a1b2c3" + "x".repeat(67), "a@b.co"));      // 73 bytes
        assertNull(PasswordPolicy.problem("a1b2c3" + "x".repeat(66), "a@b.co"));         // exactly 72 bytes
        assertNotNull(PasswordPolicy.problem("é1" + "é".repeat(36), "a@b.co"));          // 2 bytes each, over 72 bytes
    }

    @Test
    void rejectsCommonPasswordsAndOnesContainingTheEmail() {
        assertNotNull(PasswordPolicy.problem("Password123", "a@b.co"));
        assertNotNull(PasswordPolicy.problem("ashavardhan-2025", "ashavardhan@svec.edu"));
        assertNotNull(PasswordPolicy.problem("ashavardhan@svec.edu", "ashavardhan@svec.edu"));
        // a very short local part is not a useful thing to forbid
        assertNull(PasswordPolicy.problem("blue-moon-2025", "bo@svec.edu"));
    }

    /** The passwords tried first against any system: a common word, or this college's name, padded with digits and symbols. */
    @Test
    void rejectsACommonWordDressedUpWithDigitsAndSymbols() {
        for (String weak : new String[] {"Password@2026", "P@ssword12345", "Welcome#12345", "Admin@123456", "Qwerty!23456",
                "Svec@123456", "SVEC-2026-27", "Vasavi@2026!", "Sri.Vasavi.2026", "Faculty#2026", "letmein-1234", "Test@123456"}) {
            assertNotNull(PasswordPolicy.problem(weak, "someone@svec.edu"), weak + " should be refused");
        }
        // The word is fine as part of something longer; only "nothing but that word" is refused.
        assertNull(PasswordPolicy.problem("welcome-to-the-monsoon-7", "someone@svec.edu"));
        assertNull(PasswordPolicy.problem("vasavi-canteen-dosa-9", "someone@svec.edu"));
    }

    @Test
    void rejectsPasswordsMadeOfAFewRepeatedCharacters() {
        assertNotNull(PasswordPolicy.problem("aaaaaaaaa1", "someone@svec.edu"));
        assertNotNull(PasswordPolicy.problem("a1a1a1a1a1a1", "someone@svec.edu"));
        assertNotNull(PasswordPolicy.problem("xyxy1212xyxy", "someone@svec.edu"));      // four distinct characters
        assertNull(PasswordPolicy.problem("xyzw1212xyzw", "someone@svec.edu"));
    }

    /** The old password is the kind of password the policy exists to stop people choosing. */
    @Test
    void theStandardFirstPasswordCannotBeChosenAsOnesOwn() {
        assertNotNull(PasswordPolicy.problem("Srivasavi@123", "someone@svec.edu"));
    }
}
