package edu.svec.fams.auth;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/** The rules for a password a person chooses. (The old password an administrator hands out is {@link DefaultPassword}.) */
public final class PasswordPolicy {
    private PasswordPolicy() {}

    public static final int MIN_LENGTH = 10;
    /** BCrypt only reads the first 72 bytes, so longer passwords are refused rather than silently truncated. */
    public static final int MAX_BYTES = 72;

    private static final Set<String> TOO_COMMON = Set.of(
            "password123", "password1234", "1234567890", "12345678910", "qwerty12345", "qwertyuiop1",
            "welcome123", "welcome1234", "admin12345", "administrator1", "changeme123", "letmein1234", "iloveyou123");

    /**
     * Words that, padded out with digits and symbols, make the passwords tried first against any system, and against
     * this one in particular ("Password@2026", "Welcome#12345", "Svec@123456"). A password is refused when these are
     * the only letters in it, whatever surrounds them.
     */
    private static final Set<String> COMMON_WORDS = Set.of(
            "password", "passwd", "pass", "welcome", "admin", "administrator", "qwerty", "qwertyuiop", "asdfgh", "asdfghjkl",
            "letmein", "iloveyou", "changeme", "abc", "abcd", "abcde", "abcdef", "test", "testing", "user", "login", "guest",
            "college", "faculty", "teacher", "professor", "principal", "appraisal",
            "svec", "vasavi", "srivasavi", "srivasaviengineeringcollege");

    /** "aaaaaaaaa1" passes every other rule. */
    static final int MIN_DISTINCT = 5;

    private static String lettersOf(String lower) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lower.length(); i++) {
            if (Character.isLetter(lower.charAt(i))) sb.append(lower.charAt(i));
        }
        return sb.toString();
    }

    /**
     * @return a message describing the first problem, or null if the password is acceptable
     */
    public static String problem(String candidate, String email) {
        if (candidate == null || candidate.isEmpty()) return "Choose a new password.";
        if (candidate.length() < MIN_LENGTH) return "Password must be at least " + MIN_LENGTH + " characters.";
        if (candidate.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) return "Password must be at most " + MAX_BYTES + " bytes.";
        if (!candidate.equals(candidate.strip())) return "Password must not start or end with a space.";
        boolean letter = false, digit = false;
        for (int i = 0; i < candidate.length(); i++) {
            char c = candidate.charAt(i);
            letter |= Character.isLetter(c);
            digit |= Character.isDigit(c);
        }
        if (!letter || !digit) return "Password must include at least one letter and one number.";
        String lower = candidate.toLowerCase(Locale.ROOT);
        if (TOO_COMMON.contains(lower) || COMMON_WORDS.contains(lettersOf(lower))
                || COMMON_WORDS.contains(lettersOf(lower.replace('@', 'a').replace('$', 's')))) {   // "p@ssword", "pa$$word"
            return "That password is too common. Choose something less guessable.";
        }
        if (lower.chars().distinct().count() < MIN_DISTINCT) {
            return "That password repeats too few characters. Choose something less guessable.";
        }
        if (email != null) {
            String e = email.toLowerCase(Locale.ROOT);
            String local = e.contains("@") ? e.substring(0, e.indexOf('@')) : e;
            if (lower.equals(e) || (local.length() >= 4 && lower.contains(local))) return "Password must not contain your e-mail address.";
        }
        return null;
    }

}
