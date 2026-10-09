package edu.svec.fams.admin;

import edu.svec.fams.auth.FamsUserPrincipal;
import edu.svec.fams.audit.AuditService;
import edu.svec.fams.common.ApiException;
import edu.svec.fams.common.GeneratedKeys;
import edu.svec.fams.common.ValidationException;
import edu.svec.fams.scoring.Criteria;
import edu.svec.fams.scoring.ScoreService.Component;
import edu.svec.fams.section.FieldSpec;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reference data the administrator maintains: departments, academic years and the scoring policy (maximum marks per
 * cadre). Nothing here is deleted; things are deactivated, and a policy is never edited: publishing creates the next
 * version, which only appraisals started afterwards use (existing appraisals keep the maxima snapshotted on them).
 *
 * <p>A criterion's scoring components (the parts its maximum is made of, from the college's cadre-wise document) travel
 * with the policy: a new version or a new year keeps them. They always add up to the criterion's maximum, so a
 * criterion whose maximum is changed is published without its breakdown rather than with one that no longer adds up.
 */
@Service
public class AdminSetupService {

    public record Department(long id, String code, String name, boolean active) {}
    public record Cadre(long id, String code, String name, boolean active) {}
    public record Year(long id, String name, LocalDate startDate, LocalDate endDate, boolean active) {}
    public record CriterionInfo(String code, String label, boolean perEntry) {}
    public record Reference(List<Department> departments, List<Cadre> cadres, List<Year> academicYears,
                            List<CriterionInfo> criteria) {}
    /** @param components by criterion code; a criterion without a breakdown is absent */
    public record PolicyVersion(long id, long academicYearId, long cadreId, String cadre, int version, boolean active,
                                OffsetDateTime createdAt, Map<String, Integer> marks, int total,
                                Map<String, List<Component>> components) {}

    /** The most a fixed criterion (B1 to B4) can be worth; nothing in the form comes near it. */
    private static final int MAX_CRITERION_MARKS = 100;
    private static final Pattern DEPT_CODE = Pattern.compile("^[A-Z0-9]{2,16}$");
    private static final Pattern YEAR_NAME = Pattern.compile("^(\\d{4})-(\\d{2})$");
    private final JdbcClient jdbc;
    private final AuditService audit;

    public AdminSetupService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    // ---- reference lists ----

    @Transactional(readOnly = true)
    public Reference reference() {
        return new Reference(
                jdbc.sql("SELECT id, code, name, active FROM departments ORDER BY code")
                        .query((rs, n) -> new Department(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getBoolean(4))).list(),
                jdbc.sql("SELECT id, code, name, active FROM cadres ORDER BY id")
                        .query((rs, n) -> new Cadre(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getBoolean(4))).list(),
                jdbc.sql("SELECT id, name, start_date, end_date, active FROM academic_years ORDER BY start_date DESC")
                        .query((rs, n) -> new Year(rs.getLong(1), rs.getString(2), rs.getObject(3, LocalDate.class),
                                rs.getObject(4, LocalDate.class), rs.getBoolean(5))).list(),
                List.of(Criteria.values()).stream().map(c -> new CriterionInfo(c.name(), c.label(), c.perEntry())).toList());
    }

    // ---- departments ----

    @Transactional
    public Department createDepartment(FamsUserPrincipal actor, Map<String, Object> body) {
        Map<String, String> errors = new LinkedHashMap<>();
        Object code = FieldSpec.text("code", "code", "Code", 16, true).normalize(upper(body.get("code")), "code", errors);
        Object name = FieldSpec.text("name", "name", "Name", 120, true).normalize(body.get("name"), "name", errors);
        if (code != null && !DEPT_CODE.matcher((String) code).matches()) {
            errors.put("code", "Code must be 2 to 16 capital letters or digits.");
        }
        if (!errors.isEmpty()) throw new ValidationException(errors);
        long id;
        try {
            id = GeneratedKeys.insert(jdbc.sql("INSERT INTO departments (code, name) VALUES (?,?)").params(code, name));
        } catch (DuplicateKeyException e) {
            throw new ValidationException(Map.of("code", "A department with this code already exists."));
        }
        audit.recordDetails(actor.id(), "DEPARTMENT_CREATED", "DEPARTMENT", id, Map.of("code", code));
        return department(id);
    }

    /** The code is part of how departments are named on the form, so it never changes; name and active can. */
    @Transactional
    public Department updateDepartment(FamsUserPrincipal actor, long id, Map<String, Object> body) {
        Department current = department(id);
        Map<String, String> errors = new LinkedHashMap<>();
        Object name = FieldSpec.text("name", "name", "Name", 120, true).normalize(body.get("name"), "name", errors);
        boolean active = AdminInput.flag(body.get("active"), "active", errors).orElse(current.active());
        if (!errors.isEmpty()) throw new ValidationException(errors);
        jdbc.sql("UPDATE departments SET name = ?, active = ? WHERE id = ?").params(name, active, id).update();
        audit.recordDetails(actor.id(), "DEPARTMENT_UPDATED", "DEPARTMENT", id, Map.of("code", current.code(), "active", active));
        return department(id);
    }

    private Department department(long id) {
        return jdbc.sql("SELECT id, code, name, active FROM departments WHERE id = ?").param(id)
                .query((rs, n) -> new Department(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getBoolean(4)))
                .optional().orElseThrow(ApiException::notFound);
    }

    // ---- academic years ----

    /**
     * Opens a new academic year. So that appraisals can be started in it at once, every cadre's latest active policy from the
     * most recent earlier year is copied as version 1 of the new year. The administrator can then publish changes.
     */
    @Transactional
    public Year createYear(FamsUserPrincipal actor, Map<String, Object> body) {
        Map<String, String> errors = new LinkedHashMap<>();
        Object name = FieldSpec.text("name", "name", "Name", 16, true).normalize(body.get("name"), "name", errors);
        Object start = FieldSpec.date("startDate", "start_date", "Start date", true).normalize(body.get("startDate"), "startDate", errors);
        Object end = FieldSpec.date("endDate", "end_date", "End date", true).normalize(body.get("endDate"), "endDate", errors);
        if (name != null) {
            var m = YEAR_NAME.matcher((String) name);
            if (!m.matches() || (Integer.parseInt(m.group(1)) + 1) % 100 != Integer.parseInt(m.group(2))) {
                errors.put("name", "Name must look like 2027-28 (the second part is the following year).");
            }
        }
        if (start instanceof LocalDate s && end instanceof LocalDate e && !e.isAfter(s)) {
            errors.put("endDate", "End date must be after the start date.");
        }
        if (!errors.isEmpty()) throw new ValidationException(errors);

        long id;
        try {
            id = GeneratedKeys.insert(jdbc.sql("INSERT INTO academic_years (name, start_date, end_date) VALUES (?,?,?)").params(name, start, end));
        } catch (DuplicateKeyException e) {
            throw new ValidationException(Map.of("name", "An academic year with this name already exists."));
        }

        Long source = jdbc.sql("""
                SELECT p.academic_year_id FROM scoring_policies p
                JOIN academic_years ay ON ay.id = p.academic_year_id
                WHERE p.academic_year_id <> ? AND p.active = TRUE
                ORDER BY ay.start_date DESC, ay.id DESC LIMIT 1""").param(id).query(Long.class).optional().orElse(null);
        int copied = 0;
        if (source != null) {
            for (PolicyVersion p : latestPolicies(source)) {
                long policyId = insertPolicy(id, p.cadreId(), 1);
                insertCriteria(policyId, p.marks(), p);
                copied++;
            }
        }
        audit.recordDetails(actor.id(), "ACADEMIC_YEAR_CREATED", "ACADEMIC_YEAR", id,
                Map.of("name", name, "policiesCopied", copied));
        return year(id);
    }

    /** Only whether the year is open for new appraisals can change; its name and dates are part of past appraisals. */
    @Transactional
    public Year setYearActive(FamsUserPrincipal actor, long id, Map<String, Object> body) {
        Year current = year(id);
        Map<String, String> errors = new LinkedHashMap<>();
        Optional<Boolean> given = AdminInput.flag(body.get("active"), "active", errors);
        if (given.isEmpty() && errors.isEmpty()) errors.put("active", "Active is required.");
        if (!errors.isEmpty()) throw new ValidationException(errors);
        boolean active = given.orElseThrow();
        jdbc.sql("UPDATE academic_years SET active = ? WHERE id = ?").params(active, id).update();
        audit.recordDetails(actor.id(), active ? "ACADEMIC_YEAR_OPENED" : "ACADEMIC_YEAR_CLOSED", "ACADEMIC_YEAR", id,
                Map.of("name", current.name()));
        return year(id);
    }

    private Year year(long id) {
        return jdbc.sql("SELECT id, name, start_date, end_date, active FROM academic_years WHERE id = ?").param(id)
                .query((rs, n) -> new Year(rs.getLong(1), rs.getString(2), rs.getObject(3, LocalDate.class),
                        rs.getObject(4, LocalDate.class), rs.getBoolean(5)))
                .optional().orElseThrow(ApiException::notFound);
    }

    // ---- scoring policy ----

    /** Every version of every cadre's policy for the year, newest first within a cadre. */
    @Transactional(readOnly = true)
    public List<PolicyVersion> policies(long yearId) {
        year(yearId);
        return loadPolicies(yearId, false);
    }

    /**
     * Publishes a new version of one cadre's maxima. The four criteria with a maximum (B1 to B4) must be given, each a whole
     * number from 0 up; B5 to B9 are marked per entry and have none. Appraisals already started keep the version they began with.
     */
    @Transactional
    public PolicyVersion publishPolicy(FamsUserPrincipal actor, Map<String, Object> body) {
        Map<String, String> errors = new LinkedHashMap<>();
        Long yearId = AdminInput.id(body.get("academicYearId"));
        Long cadreId = AdminInput.id(body.get("cadreId"));
        if (yearId == null) errors.put("academicYearId", "Choose an academic year.");
        if (cadreId == null) errors.put("cadreId", "Choose a cadre.");
        Map<String, Integer> marks = new LinkedHashMap<>();
        Object raw = body.get("marks");
        if (!(raw instanceof Map<?, ?> given)) {
            errors.put("marks", "Give the maximum marks for every criterion.");
        } else {
            for (Object key : given.keySet()) {
                if (!(key instanceof String k) || Arrays.stream(Criteria.values()).noneMatch(c -> c.name().equals(k))) {
                    errors.put("marks", "Unknown criterion.");
                }
            }
            if (!errors.containsKey("marks")) {
                FieldSpec spec = FieldSpec.integer("marks", "max_marks", "Marks", 0, MAX_CRITERION_MARKS, true);
                for (Criteria c : Criteria.values()) {
                    if (c.perEntry()) {
                        marks.put(c.name(), 0); // marked per entry, no maximum
                        continue;
                    }
                    Object v = spec.normalize(given.get(c.name()), "marks." + c.name(), errors);
                    if (v != null) marks.put(c.name(), (Integer) v);
                }
            }
        }
        if (errors.isEmpty()) {
            if (jdbc.sql("SELECT COUNT(*) FROM academic_years WHERE id = ?").param(yearId).query(Integer.class).single() == 0) {
                errors.put("academicYearId", "Choose an academic year.");
            }
            if (jdbc.sql("SELECT COUNT(*) FROM cadres WHERE id = ? AND active = TRUE").param(cadreId).query(Integer.class).single() == 0) {
                errors.put("cadreId", "Choose a cadre.");
            }
        }
        if (!errors.isEmpty()) throw new ValidationException(errors);

        // Serialise concurrent publishes for the same year and cadre so versions are consecutive and unique.
        jdbc.sql("SELECT id FROM academic_years WHERE id = ? FOR UPDATE").param(yearId).query(Long.class).single();
        int version = jdbc.sql("SELECT COALESCE(MAX(version), 0) + 1 FROM scoring_policies WHERE academic_year_id = ? AND cadre_id = ?")
                .params(yearId, cadreId).query(Integer.class).single();
        PolicyVersion previous = loadPolicies(yearId, false).stream().filter(p -> p.cadreId() == cadreId).findFirst().orElse(null);
        long policyId = insertPolicy(yearId, cadreId, version);
        insertCriteria(policyId, marks, previous);
        audit.recordDetails(actor.id(), "SCORING_POLICY_PUBLISHED", "SCORING_POLICY", policyId,
                Map.of("academicYearId", yearId, "cadreId", cadreId, "version", version));
        return loadPolicies(yearId, false).stream().filter(p -> p.id() == policyId).findFirst().orElseThrow();
    }

    private long insertPolicy(long yearId, long cadreId, int version) {
        return GeneratedKeys.insert(jdbc.sql("INSERT INTO scoring_policies (academic_year_id, cadre_id, version) VALUES (?,?,?)")
                .params(yearId, cadreId, version));
    }

    /**
     * Writes a policy's maxima, and with each criterion the components of {@code source} (the version or year it follows
     * from) as long as its maximum is still the one they add up to.
     */
    private void insertCriteria(long policyId, Map<String, Integer> marks, PolicyVersion source) {
        for (var e : marks.entrySet()) {
            jdbc.sql("INSERT INTO scoring_policy_criteria (policy_id, criterion, max_marks) VALUES (?,?,?)")
                    .params(policyId, e.getKey(), e.getValue()).update();
            List<Component> components = source == null ? List.of() : source.components().getOrDefault(e.getKey(), List.of());
            if (components.stream().mapToInt(Component::maxMarks).sum() != e.getValue()) continue;
            int order = 1;
            for (Component c : components) {
                jdbc.sql("INSERT INTO scoring_policy_components (policy_id, criterion, sort_order, description, max_marks) VALUES (?,?,?,?,?)")
                        .params(policyId, e.getKey(), order++, c.description(), c.maxMarks()).update();
            }
        }
    }

    private List<PolicyVersion> latestPolicies(long yearId) {
        return loadPolicies(yearId, true);
    }

    private List<PolicyVersion> loadPolicies(long yearId, boolean latestActiveOnly) {
        List<PolicyVersion> result = new ArrayList<>();
        var rows = jdbc.sql("""
                SELECT p.id, p.academic_year_id, p.cadre_id, c.name, p.version, p.active, p.created_at
                FROM scoring_policies p JOIN cadres c ON c.id = p.cadre_id
                WHERE p.academic_year_id = ?
                ORDER BY p.cadre_id, p.version DESC""").param(yearId)
                .query((rs, n) -> new Object[] {rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4), rs.getInt(5),
                        rs.getBoolean(6), rs.getObject(7, OffsetDateTime.class)}).list();
        long lastCadre = -1;
        for (Object[] r : rows) {
            long cadre = (Long) r[2];
            boolean first = cadre != lastCadre;
            lastCadre = cadre;
            if (latestActiveOnly && !(first && (Boolean) r[5])) continue; // newest version of each cadre, if active
            Map<String, Integer> marks = new LinkedHashMap<>();
            jdbc.sql("SELECT criterion, max_marks FROM scoring_policy_criteria WHERE policy_id = ?").param(r[0])
                    .query((rs, n) -> Map.entry(rs.getString(1), rs.getInt(2))).list().stream()
                    .sorted((a, b) -> Integer.compare(Criteria.order(a.getKey()), Criteria.order(b.getKey())))
                    .forEach(e -> marks.put(e.getKey(), e.getValue()));
            Map<String, List<Component>> components = new LinkedHashMap<>();
            jdbc.sql("SELECT criterion, description, max_marks FROM scoring_policy_components WHERE policy_id = ? ORDER BY criterion, sort_order")
                    .param(r[0])
                    .query(rs -> {
                        components.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(new Component(rs.getString(2), rs.getInt(3)));
                    });
            result.add(new PolicyVersion((Long) r[0], (Long) r[1], cadre, (String) r[3], (Integer) r[4], (Boolean) r[5],
                    (OffsetDateTime) r[6], marks, marks.values().stream().mapToInt(Integer::intValue).sum(), components));
        }
        return result;
    }

    // ---- small helpers ----

    private static Object upper(Object o) { return o instanceof String s ? s.strip().toUpperCase(Locale.ROOT) : o; }
}
