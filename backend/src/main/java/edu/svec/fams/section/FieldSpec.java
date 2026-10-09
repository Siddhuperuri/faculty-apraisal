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

    public static FieldSpec choice(String name, String column, String label, boolean required, String... allowed) {
        FieldSpec f = new FieldSpec(name, column, label, Type.ENUM, required);
        f.allowed = List.of(allowed);
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
    public boolean submitRequired() { return submitRequired; }

    /** Marks a field that may stay empty in a draft but must be filled before the appraisal is submitted. */
    public FieldSpec forSubmission() {
        this.submitRequired = true;
        return this;
    }

    public BigDecimal min() { return min; }
    public BigDecimal max() { return max; }
    public List<String> allowed() { return allowed; }

    /**
     * @return the normalised value (String, Integer, BigDecimal, LocalDate) or null when empty/invalid.
     *         Problems are added to {@code errors} under {@code path}.
     */
    public Object normalize(Object raw, String path, Map<String, String> errors) {
        if (raw == null || (raw instanceof String s && s.isBlank())) {
            if (required) errors.put(path, label + " is required.");
            return null;
        }
        try {
            return switch (type) {
                case TEXT -> text(raw, path, errors);
                case INT -> integerValue(raw, path, errors);
                case DECIMAL -> decimalValue(raw, path, errors);
                case ENUM -> choiceValue(raw, path, errors);
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
        if (v.length() > maxLength) return fail(path, errors, label + " must be at most " + maxLength + " characters.");
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if ((c < 0x20 && c != '\n' && c != '\t' && c != '\r') || c == 0x7f) {
                return fail(path, errors, label + " contains characters that are not allowed.");
            }
        }
        return v;
    }

    private Object integerValue(Object raw, String path, Map<String, String> errors) {
        BigDecimal v = toNumber(raw);
        if (v == null || v.stripTrailingZeros().scale() > 0) {
            return fail(path, errors, label + " must be a whole number.");
        }
        if (v.compareTo(min) < 0 || v.compareTo(max) > 0) {
            return fail(path, errors, label + " must be between " + min + " and " + max + ".");
        }
        return v.intValueExact();
    }

    private Object decimalValue(Object raw, String path, Map<String, String> errors) {
        BigDecimal v = toNumber(raw);
        if (v == null) return fail(path, errors, label + " must be a number.");
        if (v.stripTrailingZeros().scale() > scale) {
            return fail(path, errors, label + " can have at most " + scale + " decimal places.");
        }
        if (v.compareTo(min) < 0 || v.compareTo(max) > 0) {
            return fail(path, errors, label + " must be between " + min.stripTrailingZeros().toPlainString()
                    + " and " + max.stripTrailingZeros().toPlainString() + ".");
        }
        return v.setScale(scale);
    }

    private Object choiceValue(Object raw, String path, Map<String, String> errors) {
        if (raw instanceof String s && allowed.contains(s)) return s;
        return fail(path, errors, label + " must be one of: " + String.join(", ", allowed) + ".");
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
