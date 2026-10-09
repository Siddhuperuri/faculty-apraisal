package edu.svec.fams.section;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** A form section backed by one table: either a list of records or a single record per appraisal. */
public final class SectionSpec {

    public record DateRange(String startField, String endField) {}

    private final String key;
    private final String table;
    private final boolean singleton;
    private final List<FieldSpec> fields;
    private final List<DateRange> dateRanges = new ArrayList<>();
    private String uniqueField;
    private Function<List<Map<String, Object>>, Map<String, Object>> summary;

    private SectionSpec(String key, String table, boolean singleton, List<FieldSpec> fields) {
        this.key = key;
        this.table = table;
        this.singleton = singleton;
        this.fields = List.copyOf(fields);
    }

    /** A repeatable list of records (add, edit, remove any number). */
    public static SectionSpec list(String key, String table, FieldSpec... fields) {
        return new SectionSpec(key, table, false, List.of(fields));
    }

    /** At most one record per appraisal. */
    public static SectionSpec single(String key, String table, FieldSpec... fields) {
        return new SectionSpec(key, table, true, List.of(fields));
    }

    /** The end date must not be before the start date. */
    public SectionSpec dateRange(String startField, String endField) {
        dateRanges.add(new DateRange(startField, endField));
        return this;
    }

    /** No two records may share a value of this field (e.g. one row per indexing platform). */
    public SectionSpec uniqueBy(String field) {
        this.uniqueField = field;
        return this;
    }

    /** Totals/averages the official form asks for, computed from the saved records, never stored. */
    public SectionSpec summary(Function<List<Map<String, Object>>, Map<String, Object>> fn) {
        this.summary = fn;
        return this;
    }

    public String key() { return key; }
    public String table() { return table; }
    public boolean singleton() { return singleton; }
    public List<FieldSpec> fields() { return fields; }
    public List<DateRange> dateRanges() { return dateRanges; }
    public String uniqueField() { return uniqueField; }
    public Function<List<Map<String, Object>>, Map<String, Object>> summary() { return summary; }

    public FieldSpec field(String name) {
        return fields.stream().filter(f -> f.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalStateException("No field " + name + " in " + key));
    }
}
