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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
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

    public SectionService(JdbcClient jdbc, AppraisalAccess access, AuditService audit) {
        this.jdbc = jdbc;
        this.access = access;
        this.audit = audit;
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
        List<Map<String, Object>> wanted = validate(spec, incoming, existing);

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

    private List<Map<String, Object>> validate(SectionSpec spec, List<Map<String, Object>> incoming,
                                               List<Map<String, Object>> existing) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (incoming == null) {
            throw new ValidationException(Map.of("records", "records is required."));
        }
        int limit = spec.singleton() ? 1 : MAX_RECORDS;
        if (incoming.size() > limit) {
            throw new ValidationException(Map.of("records", "At most " + limit + " records are allowed here."));
        }

        Set<Long> existingIds = existing.stream().map(r -> (Long) r.get("id")).collect(Collectors.toSet());
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
            for (FieldSpec f : spec.fields()) {
                rec.put(f.name(), f.normalize(raw.get(f.name()), prefix + "." + f.name(), errors));
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
                yield d == null ? null : d.setScale(f.scale());
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
