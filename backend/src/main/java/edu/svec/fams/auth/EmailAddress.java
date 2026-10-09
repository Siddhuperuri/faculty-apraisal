package edu.svec.fams.auth;

import java.util.regex.Pattern;

/** The one definition of what counts as an e-mail address when an account is created. */
public final class EmailAddress {
    private EmailAddress() {}

    private static final Pattern SHAPE = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    public static boolean isValid(String candidate) {
        return candidate != null && SHAPE.matcher(candidate).matches();
    }
}
