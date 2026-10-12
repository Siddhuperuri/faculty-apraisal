package edu.svec.fams.report;

import edu.svec.fams.common.ApiException;
import edu.svec.fams.common.ValidationException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The body of an export request, read and checked in one place: {@code {"<ids field>": [...], "format": "xlsx"|"csv",
 * "academicYearId": n}}. The ids are whole numbers; a repeated id counts once, in the order first given; at least one and
 * at most {@link #MAX_IDS} are accepted. What an id is (a faculty member, a user) is the endpoint's business, and so is
 * whether the caller may have it: nothing here is trusted as permission.
 */
public record ExportRequest(List<Long> ids, ExportFormat format, Long academicYearId) {

    /** More than any college has; a bound, not a page size. */
    public static final int MAX_IDS = 2000;

    public static ExportRequest parse(Map<String, Object> body, String idsField) {
        List<Long> ids = ids(body, idsField, MAX_IDS);
        Map<String, String> errors = new LinkedHashMap<>();
        ExportFormat format = null;
        try {
            format = ExportFormat.parse(body.get("format"));
        } catch (ApiException e) {
            errors.put("format", e.getMessage());
        }
        Long year = null;
        if (body.get("academicYearId") != null) {
            year = id(body.get("academicYearId"));
            if (year == null) errors.put("academicYearId", "Choose an academic year.");
        }
        if (!errors.isEmpty()) throw new ValidationException(errors);
        return new ExportRequest(ids, format, year);
    }

    /**
     * The ids under {@code field}: a non-empty list of positive whole numbers, repeats removed (first occurrence kept), at
     * most {@code max} of them. Anything else is a 400 naming the field.
     */
    public static List<Long> ids(Map<String, Object> body, String field, int max) {
        if (!(body.get(field) instanceof List<?> raw) || raw.isEmpty()) {
            throw new ValidationException(Map.of(field, "Select at least one row."));
        }
        if (raw.size() > max) {
            throw new ValidationException(Map.of(field, "At most " + max + " rows at a time."));
        }
        Set<Long> unique = new LinkedHashSet<>();
        for (Object o : raw) {
            Long id = id(o);
            if (id == null) throw new ValidationException(Map.of(field, "Every selected row must be a whole number."));
            unique.add(id);
        }
        return new ArrayList<>(unique);
    }

    private static Long id(Object raw) {
        if (raw instanceof Number n && n.doubleValue() == n.longValue() && n.longValue() > 0) return n.longValue();
        if (raw instanceof String s && s.matches("[0-9]{1,18}") && Long.parseLong(s) > 0) return Long.parseLong(s);
        return null;
    }
}
