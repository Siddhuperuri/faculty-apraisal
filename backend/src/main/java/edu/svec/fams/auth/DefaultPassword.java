package edu.svec.fams.auth;

import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The old password. Every account an administrator creates (one at a time or from a CSV file) starts with
 * it, and so does one whose password an administrator resets; the person must replace it at their first sign-in.
 * FAMS_DEFAULT_PASSWORD overrides it. It is never written to the log or the audit trail, and the password policy
 * refuses it as a password anyone may choose for themselves.
 */
@Component
public final class DefaultPassword {

    static final String STANDARD = "Srivasavi@123";

    private final String value;

    public DefaultPassword(@Value("${fams.default-password:}") String configured) {
        String chosen = configured == null || configured.isBlank() ? STANDARD : configured;
        if (chosen.getBytes(StandardCharsets.UTF_8).length > PasswordPolicy.MAX_BYTES) {
            throw new IllegalStateException("FAMS_DEFAULT_PASSWORD must be at most " + PasswordPolicy.MAX_BYTES + " bytes long.");
        }
        this.value = chosen;
    }

    public String value() {
        return value;
    }

    /** True when {@code candidate} is the standard password. */
    public boolean matches(String candidate) {
        return value.equals(candidate);
    }
}
