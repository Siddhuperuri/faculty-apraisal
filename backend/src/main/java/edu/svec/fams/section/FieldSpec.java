package edu.svec.fams.section;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Describes one column of a form record: its JSON name, database column, label (used in error messages),
 * type and limits. {@link #normalize} validates a raw JSON value and converts it to the value that is
 * stored and compared. Names come from code only, never from requests, so building SQL from them is safe.
 */
public final class FieldSpec {

    public enum Type { TEXT, INT, DECIMAL, ENUM, DATE, MONTH_YEAR }

    private static final Pattern MONTH_YEAR = Pattern.compile("^[0-9]{4}-(0[1-9]|1[0-2])$");
    static final int MIN_YEAR = 1950;
    static final int MAX_YEAR = 2100;

    private final String name;
    private final String column;
    private final String label;
    private final Type type;
    private final boolean required;
    private boolean submitRequired;
    private int maxLength;
    private BigDecimal min;
    private BigDecimal max;
    private int scale;
    private List<String> allowed = List.of();
    private String listCode;
    private boolean isbn;
    private int maxWords;

    private FieldSpec(String name, String column, String label, Type type, boolean required) {
        this.name = name;
        this.column = column;
        this.label = label;
        this.type = type;
        this.required = required;
    }

    public static FieldSpec text(String name, String column, String label, int maxLength, boolean required) {
        FieldSpec f = new FieldSpec(name, column, label, Type.TEXT, required);
        f.maxLength = maxLength;
        return f;
    }

    /**
     * Free text limited by its number of words (a word is a run of characters between spaces or line breaks), not by its
     * characters; a generous character ceiling only stops absurd input. The limit is the one a person is told.
     */
    public static FieldSpec words(String name, String column, String label, int maxWords, boolean required) {
        FieldSpec f = new FieldSpec(name, column, label, Type.TEXT, required);
        f.maxWords = maxWords;
        f.maxLength = maxWords * 40;
        return f;
    }

    /** The number of words in a text: runs of characters separated by spaces, tabs or line breaks. */
    public static int countWords(String text) {
        if (text == null) return 0;
        int n = 0;
        boolean inWord = false;
        for (int i = 0; i < text.length(); i++) {
            boolean space = Character.isWhitespace(text.charAt(i));
            if (!space && !inWord) n++;
            inWord = !space;
        }
        return n;
    }

    /**
     * An ISBN-10 or ISBN-13, typed with or without hyphens and spaces. It is kept as digits only (a trailing X of an ISBN-10
     * in capitals) and refused unless its length, characters and check digit are right.
     */
    public static FieldSpec isbn(String name, String column, String label, boolean required) {
        FieldSpec f = new FieldSpec(name, column, label, Type.TEXT, required);
        f.maxLength = 24;
        f.isbn = true;
        return f;
    }

    public static FieldSpec integer(String name, String column, String label, int min, int max, boolean required) {
        FieldSpec f = new FieldSpec(name, column, label, Type.INT, required);
        f.min = BigDecimal.valueOf(min);
        f.max = BigDecimal.valueOf(max);
        return f;
    }

    public static FieldSpec year(String name, String column, String label, boolean required) {
        return integer(name, column, label, MIN_YEAR, MAX_YEAR, required);
    }

    public static FieldSpec decimal(String name, String column, String label, double min, double max,
                                    int scale, boolean required) {
        FieldSpec f = new FieldSpec(name, column, label, Type.DECIMAL, required);
        f.min = BigDecimal.valueOf(min);
        f.max = BigDecimal.valueOf(max);
        f.scale = scale;
        return f;
    }

    /** A choice from a short list that is part of the application itself (it is not edited by an administrator). */
    public static FieldSpec choice(String name, String column, String label, boolean required, String... allowed) {
        FieldSpec f = new FieldSpec(name, column, label, Type.ENUM, required);
        f.allowed = List.of(allowed);
        return f;
    }

    /** A choice from a database-managed reference list: what is offered and accepted is whatever the list holds. */
    public static FieldSpec listed(String name, String column, String label, boolean required, String listCode) {
        FieldSpec f = new FieldSpec(name, column, label, Type.ENUM, required);
        f.listCode = listCode;
        return f;
    }

    public static FieldSpec date(String name, String column, String label, boolean required) {
        return new FieldSpec(name, column, label, Type.DATE, required);
    }

    public static FieldSpec monthYear(String name, String column, String label, boolean required) {
        return new FieldSpec(name, column, label, Type.MONTH_YEAR, required);
    }

    public String name() { return name; }
    public String column() { return column; }
    public Type type() { return type; }
    public int scale() { return scale; }
    public int maxLength() { return maxLength; }
    public boolean required() { return required; }
    /** The most words allowed, or 0 when the field is limited by characters. */
    public int maxWords() { return maxWords; }
    public boolean submitRequired() { return submitRequired; }

    /** Marks a field that may stay empty in a draft but must be filled before the appraisal is submitted. */
    public FieldSpec forSubmission() {
        this.submitRequired = true;
        return this;
    }

    /**
     * A number field chosen from a database-managed list of whole numbers (shown as a drop-down) rather than typed:
     * a value that is not on the list is refused, and the list, not a fixed range, sets what is allowed.
     */
    public FieldSpec fromList(String listCode) {
        this.listCode = listCode;
        return this;
    }

    public BigDecimal min() { return min; }
    public BigDecimal max() { return max; }
    public List<String> allowed() { return allowed; }

    /** The reference list this field's values come from, or null when it is not backed by one. */
    public String listCode() { return listCode; }

    /** True for an ISBN field: kept as digits and refused unless its check digit is right. */
    public boolean isbn() { return isbn; }

    /**
     * @return the normalised value (String, Integer, BigDecimal, LocalDate) or null when empty/invalid.
     *         Problems are added to {@code errors} under {@code path}.
     */
    public Object normalize(Object raw, String path, Map<String, String> errors) {
        return normalize(raw, path, errors, ChoiceSource.NONE);
    }

    /** As above, with the reference lists the field's choices are read from. */
    public Object normalize(Object raw, String path, Map<String, String> errors, ChoiceSource choices) {
        if (raw == null || (raw instanceof String s && s.isBlank())) {
            if (required) errors.put(path, label + " is required.");
            return null;
        }
        try {
            return switch (type) {
                case TEXT -> text(raw, path, errors);
                case INT -> integerValue(raw, path, errors, choices);
                case DECIMAL -> decimalValue(raw, path, errors, choices);
                case ENUM -> choiceValue(raw, path, errors, choices);
                case DATE -> dateValue(raw, path, errors);
                case MONTH_YEAR -> monthYearValue(raw, path, errors);
            };
        } catch (ArithmeticException e) {
            // A number the checks above let through but that cannot be held exactly: still the sender's mistake.
            // Anything else thrown here would be a fault in this class and must surface, not pass as bad input.
            errors.put(path, label + " is not valid.");
            return null;
        }
    }

    private Object text(Object raw, String path, Map<String, String> errors) {
        if (!(raw instanceof String s)) return fail(path, errors, label + " must be text.");
        String v = s.strip();
        if (maxWords > 0) {
            int words = countWords(v);
            if (words > maxWords) {
                return fail(path, errors, label + " must be at most " + String.format("%,d", maxWords) + " words (it has " + String.format("%,d", words) + ").");
            }
        }
        if (v.length() > maxLength) return fail(path, errors, label + " must be at most " + maxLength + " characters.");
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if ((c < 0x20 && c != '\n' && c != '\t' && c != '\r') || c == 0x7f) {
                return fail(path, errors, label + " contains characters that are not allowed.");
            }
        }
        if (isbn) {
            String digits = v.replace("-", "").replace(" ", "").toUpperCase(java.util.Locale.ROOT);
            if (!validIsbn(digits)) {
                return fail(path, errors, label + " must be a valid ISBN-10 or ISBN-13 (for example 978-0-13-468599-1).");
            }
            return digits;
        }
        return v;
    }

    /** ISBN-10: nine digits and a check digit (0 to 9 or X); ISBN-13: 978 or 979 and ten more digits; check digits verified. */
    static boolean validIsbn(String d) {
        if (d.length() == 10 && d.matches("[0-9]{9}[0-9X]")) {
            int sum = 0;
            for (int i = 0; i < 10; i++) sum += (10 - i) * (d.charAt(i) == 'X' ? 10 : d.charAt(i) - '0');
            return sum % 11 == 0;
        }
        if (d.length() == 13 && d.matches("97[89][0-9]{10}")) {
            int sum = 0;
            for (int i = 0; i < 13; i++) sum += (d.charAt(i) - '0') * (i % 2 == 0 ? 1 : 3);
            return sum % 10 == 0;
        }
        return false;
    }

    private Object integerValue(Object raw, String path, Map<String, String> errors, ChoiceSource choices) {
        BigDecimal v = toNumber(raw);
        if (v == null || v.stripTrailingZeros().scale() > 0) {
            return fail(path, errors, label + " must be a whole number.");
        }
        if (listCode != null) {
            if (!isOneOf(v, choices)) return fail(path, errors, notOnList(choices));
        }
        // the range holds even for a value on the list (a semester is 1 to 8 whatever the list is made to hold)
        if (v.compareTo(min) < 0 || v.compareTo(max) > 0) {
            return fail(path, errors, label + " must be between " + min + " and " + max + ".");
        }
        return v.intValueExact();
    }

    private Object decimalValue(Object raw, String path, Map<String, String> errors, ChoiceSource choices) {
        BigDecimal v = toNumber(raw);
        if (v == null) return fail(path, errors, label + " must be a number.");
        if (listCode != null && !isOneOf(v, choices)) return fail(path, errors, notOnList(choices));
        if (v.stripTrailingZeros().scale() > scale) {
            return fail(path, errors, label + " can have at most " + scale + " decimal places.");
        }
        if (listCode == null && (v.compareTo(min) < 0 || v.compareTo(max) > 0)) {
            return fail(path, errors, label + " must be between " + min.stripTrailingZeros().toPlainString()
                    + " and " + max.stripTrailingZeros().toPlainString() + ".");
        }
        return v.setScale(scale);
    }

    private boolean isOneOf(BigDecimal v, ChoiceSource choices) {
        return choices.active(listCode).contains(v.stripTrailingZeros().toPlainString());
    }

    private String notOnList(ChoiceSource choices) {
        return label + " must be one of: " + String.join(", ", choices.active(listCode)) + ".";
    }

    private Object choiceValue(Object raw, String path, Map<String, String> errors, ChoiceSource choices) {
        List<String> offered = listCode != null ? choices.active(listCode) : allowed;
        if (raw instanceof String s && offered.contains(s)) return s;
        return fail(path, errors, label + " must be one of: " + String.join(", ", offered) + ".");
    }

    private Object dateValue(Object raw, String path, Map<String, String> errors) {
        if (!(raw instanceof String s)) return fail(path, errors, label + " must be a date (YYYY-MM-DD).");
        try {
            LocalDate d = LocalDate.parse(s);
            if (d.getYear() < MIN_YEAR || d.getYear() > MAX_YEAR) {
                return fail(path, errors, label + " must be a year between " + MIN_YEAR + " and " + MAX_YEAR + ".");
            }
            return d;
        } catch (DateTimeParseException e) {
            return fail(path, errors, label + " must be a valid date (YYYY-MM-DD).");
        }
    }

    private Object monthYearValue(Object raw, String path, Map<String, String> errors) {
        if (raw instanceof String s && MONTH_YEAR.matcher(s).matches()) return s;
        return fail(path, errors, label + " must be a month and year (YYYY-MM).");
    }

    private static BigDecimal toNumber(Object raw) {
        if (raw instanceof Boolean) return null;
        if (raw instanceof Number || raw instanceof String) {
            try {
                return new BigDecimal(raw.toString().strip());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static Object fail(String path, Map<String, String> errors, String message) {
        errors.put(path, message);
        return null;
    }

    public String label() { return label; }
}
