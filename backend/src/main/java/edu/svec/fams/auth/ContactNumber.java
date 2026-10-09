package edu.svec.fams.auth;

import java.util.regex.Pattern;

/** The one definition of what counts as a contact number, for a person editing their own and for accounts made in bulk. */
public final class ContactNumber {
    private ContactNumber() {}

    public static final String MESSAGE = "Enter a phone number of 7 to 20 characters: digits, spaces, + ( ) or -.";

    private static final Pattern SHAPE = Pattern.compile("[0-9+()\\- ]{7,20}");

    public static boolean isValid(String candidate) {
        return candidate != null && SHAPE.matcher(candidate).matches();
    }
}
