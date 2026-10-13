package edu.svec.fams.section;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** A form section backed by one table: either a list of records or a single record per appraisal. */
public final class SectionSpec {

    public record DateRange(String startField, String endField) {}

    /** {@code daysField} is never taken from the request: it is the number of days from start to end, both included. */
    public record DerivedDays(String startField, String endField, String daysField) {}

    /** The value of {@code field} must be one of the values of reference list {@code list} that belong to the value of {@code onField}. */
    public record DependentChoice(String field, String onField, String list) {}

    /** Words offered while typing {@code field}: the list for the value of {@code onField} ({@code ""} when there is no such field). */
    public record Suggestions(String field, String onField, Map<String, String> listByValue) {}

    /** {@code field} is required (and kept) only when {@code onField} holds {@code equals}; otherwise it is cleared. */
    public record ConditionalField(String field, String onField, String equals) {}

    /**
     * {@code field} holds the wording shown and reported: the label of the value chosen in {@code codeField} (a value of
     * {@code list}), or, when that choice is "Other", the text typed in {@code otherField}. It is never taken from a request.
     */
    public record EffectiveText(String field, String codeField, String otherField, String list) {}

    private final String key;
    private final String table;
    private final boolean singleton;
    private final List<FieldSpec> fields;
    private final List<DateRange> dateRanges = new ArrayList<>();
    private String uniqueField;
    private int maxRecords = SectionService.MAX_RECORDS;
    private String recordsNoun = "records";
    private DerivedDays derivedDays;
    private DependentChoice dependentChoice;
    private final List<Suggestions> suggestions = new ArrayList<>();
    private final List<ConditionalField> conditionalFields = new ArrayList<>();
    private final List<EffectiveText> effectiveTexts = new ArrayList<>();
    private List<String> inAcademicYear = List.of();
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

    /** At most this many records (the default is {@link SectionService#MAX_RECORDS}); {@code noun} words the refusal. */
    public SectionSpec maxRecords(int max, String noun) {
        this.maxRecords = max;
        this.recordsNoun = noun;
        return this;
    }

    /** The number of days between a start and an end date is worked out by the server, whatever the request says. */
    public SectionSpec derivedDays(String startField, String endField, String daysField) {
        this.derivedDays = new DerivedDays(startField, endField, daysField);
        return this;
    }

    /** A choice that is only valid together with the value chosen in another field (a branch of its program). */
    public SectionSpec dependentChoice(String field, String onField, String list) {
        this.dependentChoice = new DependentChoice(field, onField, list);
        return this;
    }

    /** Suggest words for a text field from reference lists (free text is still allowed); keyed by the value of {@code onField}. */
    public SectionSpec suggestions(String field, String onField, Map<String, String> listByValue) {
        suggestions.add(new Suggestions(field, onField, listByValue));
        return this;
    }

    /** A field that is asked for, and required, only when another field has a given value (a platform named "Other"). */
    public SectionSpec conditionalField(String field, String onField, String equals) {
        conditionalFields.add(new ConditionalField(field, onField, equals));
        return this;
    }

    /** The wording of a role-like field comes from a dropdown that ends in "Other" with a typed text beside it. */
    public SectionSpec effectiveText(String field, String codeField, String otherField, String list) {
        effectiveTexts.add(new EffectiveText(field, codeField, otherField, list));
        return this;
    }

    /**
     * These fields (days, months or years) must fall in the academic year of the appraisal, 1 June to 31 May: only what
     * was achieved, done or received in that year is considered. A year alone must be one of the two calendar years it spans.
     */
    public SectionSpec inAcademicYear(String... fields) {
        this.inAcademicYear = List.of(fields);
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
    public int maxRecords() { return maxRecords; }
    public String recordsNoun() { return recordsNoun; }
    public DerivedDays derivedDays() { return derivedDays; }
    public DependentChoice dependentChoice() { return dependentChoice; }
    public List<ConditionalField> conditionalFields() { return conditionalFields; }
    public List<Suggestions> suggestions() { return suggestions; }
    public List<EffectiveText> effectiveTexts() { return effectiveTexts; }
    public List<String> inAcademicYear() { return inAcademicYear; }

    public FieldSpec field(String name) {
        return fields.stream().filter(f -> f.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalStateException("No field " + name + " in " + key));
    }
}
