package edu.svec.fams.section;

import edu.svec.fams.appraisal.AppraisalAccess;
import edu.svec.fams.audit.AuditService;
import edu.svec.fams.auth.FamsUserPrincipal;
import edu.svec.fams.common.ApiException;
import edu.svec.fams.common.ValidationException;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and saves the Part B form sections described in {@link Sections}.
 *
 * <p>Saving a section sends its complete list of records. Records with an {@code id} are updated (only if
 * something changed), records without one are inserted, and existing records missing from the list are
 * deleted. Everything is validated before anything is written, so a rejected request changes nothing.
 * Ids stay stable across saves.
 */
@Service
public class SectionService {

    static final int MAX_RECORDS = 200;

    public record SectionView(String section, boolean singleton, List<Map<String, Object>> records,
                              Map<String, Object> summary) {}

    private final JdbcClient jdbc;
    private final AppraisalAccess access;
    private final AuditService audit;
    private final ChoiceSource choices;

    public SectionService(JdbcClient jdbc, AppraisalAccess access, AuditService audit, ChoiceSource choices) {
        this.jdbc = jdbc;
        this.access = access;
        this.audit = audit;
        this.choices = choices;
    }

    @Transactional(readOnly = true)
    public SectionView read(long appraisalId, String key, FamsUserPrincipal user) {
        SectionSpec spec = spec(key);
        access.loadVisible(appraisalId, user);
        return view(spec, appraisalId);
    }

    /** Every section of an appraisal, in the form's order, behind one access check (the report needs them all). */
    @Transactional(readOnly = true)
    public Map<String, SectionView> readAll(long appraisalId, FamsUserPrincipal user) {
        access.loadVisible(appraisalId, user);
        Map<String, SectionView> out = new LinkedHashMap<>();
        for (SectionSpec s : Sections.all().values()) out.put(s.key(), view(s, appraisalId));
        return out;
    }

    /** Number of saved records per section, for the progress sidebar. One statement, not a round trip per table. */
    @Transactional(readOnly = true)
    public Map<String, Integer> counts(long appraisalId, FamsUserPrincipal user) {
        access.loadVisible(appraisalId, user);
        List<SectionSpec> all = List.copyOf(Sections.all().values());
        String sql = all.stream()
                .map(s -> "SELECT ? AS section, COUNT(*) AS records FROM " + q(s.table()) + " WHERE appraisal_id = ?")
                .collect(Collectors.joining(" UNION ALL "));
        List<Object> params = new ArrayList<>();
        for (SectionSpec s : all) {
            params.add(s.key());
            params.add(appraisalId);
        }
        Map<String, Integer> counted = new HashMap<>();
        jdbc.sql(sql).params(params).query(rs -> {
            counted.put(rs.getString("section"), rs.getInt("records"));
        });
        Map<String, Integer> out = new LinkedHashMap<>();
        for (SectionSpec s : all) out.put(s.key(), counted.getOrDefault(s.key(), 0));
        return out;
    }

    @Transactional
    public SectionView save(long appraisalId, String key, FamsUserPrincipal user,
                            List<Map<String, Object>> incoming) {
        SectionSpec spec = spec(key);
        access.loadEditable(appraisalId, user); // locks the appraisal row until commit

        List<Map<String, Object>> existing = loadRows(spec, appraisalId);
        List<Map<String, Object>> wanted = validate(spec, appraisalId, incoming, existing);

        int changes = spec.singleton() ? applySingle(spec, appraisalId, existing, wanted)
                                       : applyList(spec, appraisalId, existing, wanted);
        if (changes > 0) {
            jdbc.sql("UPDATE appraisals SET updated_at = CURRENT_TIMESTAMP WHERE id = ?").param(appraisalId).update();
            audit.record(user.id(), "SECTION_SAVED", "APPRAISAL", appraisalId,
                    "{\"section\":\"" + spec.key() + "\",\"changes\":" + changes + "}");
        }
        return view(spec, appraisalId);
    }

    // ---- validation ----

    private List<Map<String, Object>> validate(SectionSpec spec, long appraisalId, List<Map<String, Object>> incoming,
                                               List<Map<String, Object>> existing) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (incoming == null) {
            throw new ValidationException(Map.of("records", "records is required."));
        }
        int limit = spec.singleton() ? 1 : spec.maxRecords();
        if (incoming.size() > limit) {
            String noun = spec.singleton() ? "records" : spec.recordsNoun();
            throw new ValidationException(Map.of("records", "At most " + limit + " " + noun + " are allowed here."));
        }
        AcademicYear year = spec.inAcademicYear().isEmpty() ? null : academicYear(appraisalId);

        Set<Long> existingIds = existing.stream().map(r -> (Long) r.get("id")).collect(Collectors.toSet());
        Map<Long, Map<String, Object>> existingById = new HashMap<>();
        for (Map<String, Object> r : existing) existingById.put((Long) r.get("id"), r);
        Set<Long> seenIds = new HashSet<>();
        Set<Object> seenUnique = new HashSet<>();
        Set<String> known = spec.fields().stream().map(FieldSpec::name).collect(Collectors.toSet());
        List<Map<String, Object>> out = new ArrayList<>();

        for (int i = 0; i < incoming.size(); i++) {
            Map<String, Object> raw = incoming.get(i);
            String prefix = "records[" + i + "]";
            if (raw == null) {
                errors.put(prefix, "Record is empty.");
                continue;
            }
            for (String k : raw.keySet()) {
                if (!known.contains(k) && !k.equals("id")) errors.put(prefix + "." + k, "Not a recognised field.");
            }

            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("id", spec.singleton() ? null : validateId(raw.get("id"), prefix, existingIds, seenIds, errors));
            SectionSpec.DerivedDays derived = spec.derivedDays();
            Map<String, Object> before = rec.get("id") == null ? null : existingById.get((Long) rec.get("id"));
            for (FieldSpec f : spec.fields()) {
                if (derived != null && f.name().equals(derived.daysField())) continue;   // worked out below
                if (isEffective(spec, f.name())) continue;                                // worked out below
                Object raw1 = raw.get(f.name());
                if (keepsSavedValue(f, raw1, before)) {
                    rec.put(f.name(), before.get(f.name()));
                } else {
                    rec.put(f.name(), f.normalize(raw1, prefix + "." + f.name(), errors, choices));
                }
            }
            if (derived != null) rec.put(derived.daysField(), derivedDays(spec, derived, rec, prefix, errors));

            SectionSpec.DependentChoice dependent = spec.dependentChoice();
            boolean pairUnchanged = before != null && dependent != null
                    && Objects.equals(before.get(dependent.field()), rec.get(dependent.field()))
                    && Objects.equals(before.get(dependent.onField()), rec.get(dependent.onField()));
            if (dependent != null && !pairUnchanged && rec.get(dependent.field()) instanceof String chosen
                    && rec.get(dependent.onField()) instanceof String on
                    && !choices.activeUnder(dependent.list(), on).contains(chosen)) {
                errors.putIfAbsent(prefix + "." + dependent.field(), spec.field(dependent.field()).label()
                        + " is not offered under the " + spec.field(dependent.onField()).label().toLowerCase(Locale.ROOT) + " you chose.");
            }

            for (SectionSpec.ConditionalField c : spec.conditionalFields()) {
                boolean asked = c.equals().equals(rec.get(c.onField()));
                String onLabel = spec.field(c.onField()).label();
                if (!asked) {
                    Object sent = raw.get(c.field());
                    boolean given = sent != null && !(sent instanceof String s && s.isBlank());
                    if (c.disabled() && given && rec.get(c.onField()) != null) {
                        // the form shows it greyed out and empty; a value here is a mistake that must not be kept
                        errors.put(prefix + "." + c.field(), spec.field(c.field()).label() + " must be left empty unless "
                                + onLabel + " is " + valueLabel(spec, c) + ".");
                    }
                    rec.put(c.field(), null);   // not asked for under the other choice, so nothing is kept
                } else if (rec.get(c.field()) == null) {
                    errors.putIfAbsent(prefix + "." + c.field(), spec.field(c.field()).label() + " is required when "
                            + onLabel + " is " + valueLabel(spec, c) + ".");
                }
            }

            for (SectionSpec.EffectiveText e : spec.effectiveTexts()) {
                String code = rec.get(e.codeField()) instanceof String c ? c : null;
                Object typed = rec.get(e.otherField());
                String wording = null;
                if ("OTHER".equals(code)) {
                    wording = typed instanceof String t ? t : null;
                } else if (code != null) {
                    wording = choices.labels(e.list()).get(code);
                    if (wording == null && before != null && code.equals(before.get(e.codeField()))) wording = (String) before.get(e.field());
                }
                if (wording != null && wording.length() > spec.field(e.field()).maxLength()) {
                    wording = wording.substring(0, spec.field(e.field()).maxLength());
                }
                rec.put(e.field(), wording);
            }

            for (SectionSpec.DateRange r : spec.dateRanges()) {
                Object s = rec.get(r.startField());
                Object e = rec.get(r.endField());
                if (s instanceof LocalDate sd && e instanceof LocalDate ed && ed.isBefore(sd)) {
                    String startLabel = spec.field(r.startField()).label();
                    errors.putIfAbsent(prefix + "." + r.endField(), spec.field(r.endField()).label()
                            + " must not be before " + Character.toLowerCase(startLabel.charAt(0))
                            + startLabel.substring(1) + ".");
                }
            }
            if (year != null) {
                for (String name : spec.inAcademicYear()) {
                    String problem = outsideAcademicYear(spec.field(name).label(), rec.get(name), year);
                    if (problem != null) errors.putIfAbsent(prefix + "." + name, problem);
                }
            }
            if (spec.uniqueField() != null) {
                Object u = rec.get(spec.uniqueField());
                if (u != null && !seenUnique.add(u)) {
                    errors.putIfAbsent(prefix + "." + spec.uniqueField(),
                            spec.field(spec.uniqueField()).label() + " is already used in another row.");
                }
            }
            out.add(rec);
        }
        if (!errors.isEmpty()) throw new ValidationException(errors);
        return out;
    }

    /** How the value that asks for a conditional field is worded in a message: "Other", "Sanctioned". */
    private String valueLabel(SectionSpec spec, SectionSpec.ConditionalField c) {
        FieldSpec on = spec.field(c.onField());
        String label = on.listCode() != null ? choices.labels(on.listCode()).get(c.equals()) : null;
        if (label != null) return label;
        return "OTHER".equals(c.equals()) ? "Other" : c.equals();
    }

    private static boolean isEffective(SectionSpec spec, String field) {
        return spec.effectiveTexts().stream().anyMatch(e -> e.field().equals(field));
    }

    /**
     * A value a list no longer offers (it was switched off) may stay on a record that already holds it: only a new or
     * changed choice has to be one of the values offered now.
     */
    private boolean keepsSavedValue(FieldSpec f, Object sent, Map<String, Object> before) {
        if (before == null || f.listCode() == null || sent == null) return false;
        Object saved = before.get(f.name());
        if (saved == null) return false;
        BigDecimal a = null;
        BigDecimal b = null;
        if (saved instanceof Number n1 && sent instanceof Number n2) {
            a = new BigDecimal(n1.toString());
            b = new BigDecimal(n2.toString());
        }
        boolean same = a != null ? a.compareTo(b) == 0 : saved.toString().equals(sent.toString());
        String code = saved instanceof BigDecimal d ? d.stripTrailingZeros().toPlainString() : saved.toString();
        return same && choices.known(f.listCode(), code);
    }

    /** From the start date to the end date, both days counted; null while either date is missing or the range is reversed. */
    private static Integer derivedDays(SectionSpec spec, SectionSpec.DerivedDays d, Map<String, Object> rec, String prefix,
                                       Map<String, String> errors) {
        if (!(rec.get(d.startField()) instanceof LocalDate start) || !(rec.get(d.endField()) instanceof LocalDate end) || end.isBefore(start)) {
            return null;
        }
        long days = ChronoUnit.DAYS.between(start, end) + 1;
        FieldSpec field = spec.field(d.daysField());
        if (field.max() != null && days > field.max().longValue()) {
            errors.putIfAbsent(prefix + "." + d.endField(), field.label() + " can be at most " + field.max() + ".");
            return null;
        }
        return (int) days;
    }

    /** The academic year an appraisal is for: its name and its first and last day (1 June 2025 to 31 May 2026 for 2025-26). */
    private record AcademicYear(String name, LocalDate start, LocalDate end) {}

    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    private AcademicYear academicYear(long appraisalId) {
        return jdbc.sql("""
                SELECT ay.name, ay.start_date, ay.end_date FROM appraisals a
                JOIN academic_years ay ON ay.id = a.academic_year_id WHERE a.id = ?""")
                .param(appraisalId).query((rs, n) -> new AcademicYear(rs.getString(1), rs.getObject(2, LocalDate.class), rs.getObject(3, LocalDate.class))).single();
    }

    /** What is wrong with a day, a month (YYYY-MM) or a year that must fall in the academic year; null when it does (or is empty). */
    static String outsideAcademicYear(String label, Object value, AcademicYear ay) {
        String within = " must be within the academic year " + ay.name() + " (" + DMY.format(ay.start()) + " to " + DMY.format(ay.end()) + ").";
        if (value instanceof LocalDate d) {
            return d.isBefore(ay.start()) || d.isAfter(ay.end()) ? label + within : null;
        }
        if (value instanceof String s) {   // a month and year, YYYY-MM
            YearMonth m = YearMonth.parse(s);
            return m.isBefore(YearMonth.from(ay.start())) || m.isAfter(YearMonth.from(ay.end())) ? label + within : null;
        }
        if (value instanceof Integer y && (y < ay.start().getYear() || y > ay.end().getYear())) {
            int a = ay.start().getYear(), b = ay.end().getYear();
            return label + " must be " + (a == b ? String.valueOf(a) : a + " or " + b) + ", the years of the academic year " + ay.name() + ".";
        }
        return null;
    }

    private static Long validateId(Object raw, String prefix, Set<Long> existingIds, Set<Long> seenIds,
                                   Map<String, String> errors) {
        if (raw == null) return null;
        Long id = null;
        if (raw instanceof Number n && n.doubleValue() == n.longValue()) id = n.longValue();
        if (id == null) {
            errors.put(prefix + ".id", "Record id is not valid.");
        } else if (!existingIds.contains(id)) {
            // Also covers ids that belong to another appraisal: they are simply not in this one.
            errors.put(prefix + ".id", "This record does not exist in this appraisal.");
        } else if (!seenIds.add(id)) {
            errors.put(prefix + ".id", "This record appears more than once.");
        }
        return id;
    }

    // ---- writing ----

    private int applyList(SectionSpec spec, long appraisalId, List<Map<String, Object>> existing,
                          List<Map<String, Object>> wanted) {
        Map<Long, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> r : existing) byId.put((Long) r.get("id"), r);
        Set<Long> kept = wanted.stream().map(r -> (Long) r.get("id")).filter(Objects::nonNull)
                .collect(Collectors.toSet());

        int changes = 0;
        for (Long id : byId.keySet()) {
            if (!kept.contains(id)) {
                delete(spec, appraisalId, id);
                changes++;
            }
        }
        for (Map<String, Object> rec : wanted) {
            Long id = (Long) rec.get("id");
            if (id == null) {
                insert(spec, appraisalId, rec);
                changes++;
            } else if (!sameValues(spec, byId.get(id), rec)) {
                update(spec, appraisalId, id, rec);
                changes++;
            }
        }
        return changes;
    }

    private int applySingle(SectionSpec spec, long appraisalId, List<Map<String, Object>> existing,
                            List<Map<String, Object>> wanted) {
        Map<String, Object> current = existing.isEmpty() ? null : existing.get(0);
        if (wanted.isEmpty()) {
            if (current == null) return 0;
            delete(spec, appraisalId, (Long) current.get("id"));
            return 1;
        }
        Map<String, Object> rec = wanted.get(0);
        if (current == null) {
            insert(spec, appraisalId, rec);
            return 1;
        }
        if (sameValues(spec, current, rec)) return 0;
        update(spec, appraisalId, (Long) current.get("id"), rec);
        return 1;
    }

    private boolean sameValues(SectionSpec spec, Map<String, Object> a, Map<String, Object> b) {
        for (FieldSpec f : spec.fields()) {
            if (!Objects.equals(a.get(f.name()), b.get(f.name()))) return false;
        }
        return true;
    }

    private void insert(SectionSpec spec, long appraisalId, Map<String, Object> rec) {
        String cols = spec.fields().stream().map(f -> q(f.column())).collect(Collectors.joining(", "));
        String marks = "?" + ", ?".repeat(spec.fields().size());
        List<Object> params = new ArrayList<>();
        params.add(appraisalId);
        for (FieldSpec f : spec.fields()) params.add(rec.get(f.name()));
        jdbc.sql("INSERT INTO " + q(spec.table()) + " (appraisal_id, " + cols + ") VALUES (" + marks + ")")
                .params(params).update();
    }

    private void update(SectionSpec spec, long appraisalId, long id, Map<String, Object> rec) {
        String sets = spec.fields().stream().map(f -> q(f.column()) + " = ?").collect(Collectors.joining(", "));
        List<Object> params = new ArrayList<>();
        for (FieldSpec f : spec.fields()) params.add(rec.get(f.name()));
        params.add(id);
        params.add(appraisalId);
        jdbc.sql("UPDATE " + q(spec.table()) + " SET " + sets + " WHERE id = ? AND appraisal_id = ?")
                .params(params).update();
    }

    private void delete(SectionSpec spec, long appraisalId, long id) {
        jdbc.sql("DELETE FROM " + q(spec.table()) + " WHERE id = ? AND appraisal_id = ?")
                .params(id, appraisalId).update();
    }

    // ---- reading ----

    private SectionView view(SectionSpec spec, long appraisalId) {
        List<Map<String, Object>> rows = loadRows(spec, appraisalId);
        Map<String, Object> summary = spec.summary() == null ? null : spec.summary().apply(rows);
        return new SectionView(spec.key(), spec.singleton(), rows, summary);
    }

    private List<Map<String, Object>> loadRows(SectionSpec spec, long appraisalId) {
        String cols = spec.fields().stream().map(f -> q(f.column())).collect(Collectors.joining(", "));
        return jdbc.sql("SELECT id, " + cols + " FROM " + q(spec.table()) + " WHERE appraisal_id = ? ORDER BY id")
                .param(appraisalId)
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    for (FieldSpec f : spec.fields()) m.put(f.name(), read(rs, f));
                    // A value kept from before the rule (an amount against a project that is only applied for) is not shown.
                    for (SectionSpec.ConditionalField c : spec.conditionalFields()) {
                        if (!c.equals().equals(m.get(c.onField()))) m.put(c.field(), null);
                    }
                    return m;
                }).list();
    }

    private static Object read(ResultSet rs, FieldSpec f) throws SQLException {
        String c = f.column();
        return switch (f.type()) {
            case TEXT, ENUM, MONTH_YEAR -> rs.getString(c);
            case INT -> rs.getObject(c) == null ? null : rs.getInt(c);
            case DECIMAL -> {
                BigDecimal d = rs.getBigDecimal(c);
                // A value saved before the field's scale was reduced (3.5 hours a week) is shown as it was, not cut.
                yield d == null ? null
                        : d.stripTrailingZeros().scale() > f.scale() ? d : d.setScale(f.scale(), java.math.RoundingMode.UNNECESSARY);
            }
            case DATE -> rs.getObject(c, LocalDate.class);
        };
    }

    private static SectionSpec spec(String key) {
        SectionSpec s = Sections.get(key);
        if (s == null) throw ApiException.notFound();
        return s;
    }

    /** Identifiers come only from {@link Sections} (code), never from requests. */
    private static String q(String identifier) { return "`" + identifier + "`"; }
}
