package edu.svec.fams.reference;

import edu.svec.fams.audit.AuditService;
import edu.svec.fams.common.ApiException;
import edu.svec.fams.common.ValidationException;
import edu.svec.fams.section.ChoiceSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The dropdown and reference lists kept in the database (tables reference_lists and reference_values), read by the forms,
 * the validation and the printed report, and managed by an administrator. A snapshot is kept in memory and re-read when an
 * administrator changes a list and, as a safety net for another instance, every half minute.
 *
 * <p>Values are never deleted: a value that is switched off is no longer offered, but records that hold it keep it and
 * its label. A locked value is one the application's own rules depend on; its label can change but it stays on.
 */
@Service
public class ReferenceData implements ChoiceSource {

    private static final long MAX_AGE_NANOS = 30_000_000_000L;
    private static final Pattern CODE = Pattern.compile("^[A-Z0-9][A-Z0-9_.]{0,59}$");
    private static final Pattern NUMBER = Pattern.compile("^[1-9][0-9]{0,2}$");

    public record ListInfo(String code, String name, String description, String parentList, String kind) {}

    public record Value(long id, String list, String code, String parent, String label, String reportLabel, int sortOrder,
                        boolean active, boolean locked) {}

    /** A list with every value, switched on or off, for the administration page. */
    public record ListView(String code, String name, String description, String parentList, String kind, List<Value> values) {}

    private record Snapshot(Map<String, ListInfo> lists, Map<String, List<Value>> values, Map<String, String> reportLabels,
                            long loadedAt) {}

    private final JdbcClient jdbc;
    private final AuditService audit;
    private volatile Snapshot snapshot;

    public ReferenceData(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    // ---- reading ----

    private Snapshot snap() {
        Snapshot s = snapshot;
        if (s == null || System.nanoTime() - s.loadedAt > MAX_AGE_NANOS) s = load();
        return s;
    }

    /** Re-reads the lists now (after a change, and in tests). */
    public synchronized void refresh() { load(); }

    private synchronized Snapshot load() {
        Map<String, ListInfo> lists = new LinkedHashMap<>();
        jdbc.sql("SELECT code, name, description, parent_list, kind FROM reference_lists ORDER BY code")
                .query((rs, n) -> new ListInfo(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)))
                .list().forEach(l -> lists.put(l.code(), l));
        Map<String, List<Value>> values = new LinkedHashMap<>();
        for (String l : lists.keySet()) values.put(l, new ArrayList<>());
        Map<String, String> report = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT id, list_code, code, parent_code, label, report_label, sort_order, active, locked
                FROM reference_values ORDER BY list_code, sort_order, id""")
                .query((rs, n) -> new Value(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getInt(7), rs.getBoolean(8), rs.getBoolean(9)))
                .list().forEach(v -> {
                    values.computeIfAbsent(v.list(), k -> new ArrayList<>()).add(v);
                    ListInfo info = lists.get(v.list());
                    if (info != null && !"FREE".equals(info.kind())) {
                        report.putIfAbsent(v.code(), v.reportLabel() != null ? v.reportLabel() : v.label());
                    }
                });
        Snapshot s = new Snapshot(lists, values, report, System.nanoTime());
        snapshot = s;
        return s;
    }

    private List<Value> of(String list) { return snap().values().getOrDefault(list, List.of()); }

    @Override
    public List<String> active(String list) {
        return of(list).stream().filter(Value::active).map(Value::code).distinct().toList();
    }

    @Override
    public List<String> activeUnder(String list, String parent) {
        return of(list).stream().filter(v -> v.active() && v.parent().equals(parent)).map(Value::code).toList();
    }

    @Override
    public Map<String, List<String>> activeByParent(String list) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Value v : of(list)) {
            if (v.active()) out.computeIfAbsent(v.parent(), k -> new ArrayList<>()).add(v.code());
        }
        return out;
    }

    @Override
    public boolean known(String list, String code) {
        return of(list).stream().anyMatch(v -> v.code().equals(code));
    }

    @Override
    public Map<String, String> labels(String list) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Value v : of(list)) out.putIfAbsent(v.code(), v.label());
        return out;
    }

    /** The wording printed on the report for a stored code: the report label, or the screen label, or null when no list has it. */
    public String reportLabel(String code) { return snap().reportLabels().get(code); }

    // ---- administration ----

    @Transactional(readOnly = true)
    public List<ListView> all() {
        Snapshot s = load();
        List<ListView> out = new ArrayList<>();
        for (ListInfo l : s.lists().values()) {
            out.add(new ListView(l.code(), l.name(), l.description(), l.parentList(), l.kind(), List.copyOf(s.values().get(l.code()))));
        }
        return out;
    }

    @Transactional
    public Value add(long actorId, String listCode, Map<String, Object> body) {
        ListInfo list = snap().lists().get(listCode);
        if (list == null) throw ApiException.notFound();
        Map<String, String> errors = new LinkedHashMap<>();

        String label = text(body.get("label"));
        if (label == null) {
            errors.put("label", "Label is required.");
        } else if (label.length() > 160) {
            errors.put("label", "Label must be at most 160 characters.");
        }
        String reportLabel = text(body.get("reportLabel"));
        if (reportLabel != null && reportLabel.length() > 160) errors.put("reportLabel", "Report wording must be at most 160 characters.");

        String code = text(body.get("code"));
        if ("FREE".equals(list.kind())) {
            code = label;   // a suggestion is its own words
        } else if (code == null) {
            errors.put("code", "Code is required.");
        } else if ("NUMBER".equals(list.kind())) {
            if (!NUMBER.matcher(code).matches()) errors.put("code", "Code must be a whole number from 1 to 999.");
        } else {
            code = code.toUpperCase();
            if (!CODE.matcher(code).matches()) {
                errors.put("code", "Code must be capital letters, digits and underscores, starting with a letter or digit (at most 60).");
            }
        }

        String parent = text(body.get("parent"));
        if (list.parentList() != null) {
            if (parent == null) {
                errors.put("parent", "Choose the " + snap().lists().get(list.parentList()).name().toLowerCase() + " this belongs to.");
            } else if (!known(list.parentList(), parent)) {
                errors.put("parent", "That is not a value of the " + list.parentList() + " list.");
            }
        } else {
            parent = "";
        }
        if (!errors.isEmpty()) throw new ValidationException(errors);

        String finalCode = code;
        String finalParent = parent;
        boolean exists = of(listCode).stream().anyMatch(v -> v.code().equalsIgnoreCase(finalCode) && v.parent().equals(finalParent));
        if (exists) {
            throw new ValidationException(Map.of("code", "This list already has that value" + (parent.isEmpty() ? "." : " under that choice.")));
        }
        int next = of(listCode).stream().mapToInt(Value::sortOrder).max().orElse(0) + 10;
        jdbc.sql("""
                INSERT INTO reference_values (list_code, code, parent_code, label, report_label, sort_order, created_by)
                VALUES (?,?,?,?,?,?,?)""")
                .params(listCode, finalCode, finalParent, label, reportLabel, next, actorId).update();
        audit.recordDetails(actorId, "REFERENCE_VALUE_ADDED", "REFERENCE_LIST", null,
                Map.of("list", listCode, "code", finalCode, "parent", finalParent, "label", label));
        Value created = load().values().get(listCode).stream()
                .filter(v -> v.code().equals(finalCode) && v.parent().equals(finalParent)).findFirst().orElseThrow();
        return created;
    }

    /** Changes the wording, order or on/off state of a value. Its code never changes. A locked value cannot be switched off. */
    @Transactional
    public Value update(long actorId, long id, Map<String, Object> body) {
        Value current = snap().values().values().stream().flatMap(List::stream).filter(v -> v.id() == id).findFirst()
                .orElseThrow(ApiException::notFound);
        Map<String, String> errors = new LinkedHashMap<>();

        String label = body.containsKey("label") ? text(body.get("label")) : current.label();
        if (label == null) {
            errors.put("label", "Label is required.");
        } else if (label.length() > 160) {
            errors.put("label", "Label must be at most 160 characters.");
        }
        String reportLabel = body.containsKey("reportLabel") ? text(body.get("reportLabel")) : current.reportLabel();
        if (reportLabel != null && reportLabel.length() > 160) errors.put("reportLabel", "Report wording must be at most 160 characters.");

        int sortOrder = current.sortOrder();
        if (body.containsKey("sortOrder")) {
            if (body.get("sortOrder") instanceof Number n && n.doubleValue() == n.intValue() && n.intValue() >= 0 && n.intValue() <= 100000) {
                sortOrder = n.intValue();
            } else {
                errors.put("sortOrder", "Order must be a whole number from 0 to 100000.");
            }
        }
        boolean active = current.active();
        if (body.containsKey("active")) {
            if (body.get("active") instanceof Boolean b) active = b;
            else errors.put("active", "Active must be true or false.");
        }
        if (!active && current.locked()) {
            errors.put("active", "This value is used by the application's own rules, so it cannot be switched off. You can change its wording.");
        }
        ListInfo list = snap().lists().get(current.list());
        if ("FREE".equals(list.kind()) && body.containsKey("label") && label != null && !label.equals(current.label())) {
            // a suggestion is its own words: renaming it is replacing it, which is done by adding the new one and switching this off
            errors.put("label", "A suggestion cannot be renamed: add the new wording and switch this one off.");
        }
        if (!errors.isEmpty()) throw new ValidationException(errors);

        jdbc.sql("UPDATE reference_values SET label = ?, report_label = ?, sort_order = ?, active = ? WHERE id = ?")
                .params(label, reportLabel, sortOrder, active, id).update();
        audit.recordDetails(actorId, "REFERENCE_VALUE_UPDATED", "REFERENCE_LIST", id,
                Map.of("list", current.list(), "code", current.code(), "label", label, "active", active, "sortOrder", sortOrder));
        return load().values().get(current.list()).stream().filter(v -> v.id() == id).findFirst().orElseThrow();
    }

    private static String text(Object raw) {
        if (!(raw instanceof String s)) return null;
        String v = s.strip();
        return v.isEmpty() ? null : v;
    }

    /** Every code currently used by a list, for tests and the administration page's counts. */
    public LinkedHashSet<String> codes(String list) {
        return new LinkedHashSet<>(of(list).stream().map(Value::code).toList());
    }
}
