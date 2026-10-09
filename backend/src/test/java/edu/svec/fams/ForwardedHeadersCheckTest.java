package edu.svec.fams;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.svec.fams.common.ForwardedHeadersCheck;
import org.junit.jupiter.api.Test;

class ForwardedHeadersCheckTest {

    @Test
    void theStrategyThatBelievesClientSuppliedHeadersStopsTheApplicationFromStarting() {
        for (String value : new String[] {"framework", "FRAMEWORK", " Framework "}) {
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> new ForwardedHeadersCheck(value, "127[.]0[.]0[.]1"));
            assertTrue(e.getMessage().contains("native"), "the message says what to use instead: " + e.getMessage());
        }
    }

    @Test
    void theSafeSettingsStart() {
        assertDoesNotThrow(() -> new ForwardedHeadersCheck("none", ""));
        assertDoesNotThrow(() -> new ForwardedHeadersCheck("native", "127[.]0[.]0[.]1|::1"));
    }
}
