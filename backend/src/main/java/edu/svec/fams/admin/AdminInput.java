package edu.svec.fams.admin;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** Reading the loosely typed values of an administration request body (a JSON object read as a map). */
final class AdminInput {
    private AdminInput() {}

    private static final Pattern DIGITS = Pattern.compile("[0-9]{1,18}");

    /** A positive whole number given as a JSON number or a string of digits; otherwise null. */
    static Long id(Object raw) {
        if (raw instanceof Number n && n.doubleValue() == n.longValue() && n.longValue() > 0) return n.longValue();
        if (raw instanceof String s && DIGITS.matcher(s).matches() && Long.parseLong(s) > 0) return Long.parseLong(s);
        return null;
    }

    /** Empty when absent; empty with an error under {@code field} when present but not a boolean. */
    static Optional<Boolean> flag(Object raw, String field, Map<String, String> errors) {
        if (raw == null) return Optional.empty();
        if (raw instanceof Boolean b) return Optional.of(b);
        errors.put(field, "Must be true or false.");
        return Optional.empty();
    }
}
