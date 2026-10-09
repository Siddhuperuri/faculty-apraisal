package edu.svec.fams.ops;

import edu.svec.fams.audit.AuditService;
import edu.svec.fams.auth.FamsUserPrincipal;
import edu.svec.fams.common.ValidationException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The facts an administrator taking over (or standing in) needs and the application cannot know: who looks after the server,
 * whom to call, where the spare machine for restore tests is. Entered by administrators, kept with everything else.
 * No passwords or keys belong here, and the page says so.
 */
@Service
public class HandoverService {

    /** The notes that can be set, in the order the page shows them, with the question each answers. */
    public static final Map<String, String> FIELDS = Map.of(
            "serverManager", "Who manages the server",
            "serverLocation", "Where the server is",
            "supportContact", "Who to call for support",
            "backupCustodian", "Who looks after the backups",
            "restoreMachine", "Where restore tests are done",
            "other", "Anything else the next administrator should know");
    public static final List<String> ORDER = List.of("serverManager", "serverLocation", "supportContact", "backupCustodian", "restoreMachine", "other");
    private static final int MAX = 1000;

    public record Note(String key, String question, String value, String updatedBy, OffsetDateTime updatedAt) {}

    private final JdbcClient jdbc;
    private final AuditService audit;

    public HandoverService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Note> notes() {
        Map<String, Note> saved = new LinkedHashMap<>();
        jdbc.sql("SELECT n.note_key, n.note_value, COALESCE(u.name, u.email) AS who, n.updated_at FROM handover_notes n JOIN users u ON u.id = n.updated_by")
                .query((rs, i) -> {
                    saved.put(rs.getString("note_key"), new Note(rs.getString("note_key"), FIELDS.get(rs.getString("note_key")),
                            rs.getString("note_value"), rs.getString("who"), rs.getObject("updated_at", OffsetDateTime.class)));
                    return null;
                }).list();
        List<Note> out = new ArrayList<>();
        for (String key : ORDER) out.add(saved.getOrDefault(key, new Note(key, FIELDS.get(key), "", null, null)));
        return out;
    }

    /** Sets the notes given; an empty text clears a note. Unknown keys are ignored. */
    @Transactional
    public List<Note> save(FamsUserPrincipal actor, Map<String, Object> body) {
        Map<String, String> errors = new LinkedHashMap<>();
        Map<String, String> changes = new LinkedHashMap<>();
        for (String key : ORDER) {
            if (!body.containsKey(key)) continue;
            Object raw = body.get(key);
            String value = raw == null ? "" : raw instanceof String s ? s.strip() : null;
            if (value == null) {
                errors.put(key, "Enter text.");
            } else if (value.length() > MAX) {
                errors.put(key, "At most " + MAX + " characters.");
            } else {
                changes.put(key, value);
            }
        }
        if (!errors.isEmpty()) throw new ValidationException(errors);
        for (var change : changes.entrySet()) {
            if (change.getValue().isEmpty()) {
                jdbc.sql("DELETE FROM handover_notes WHERE note_key = ?").param(change.getKey()).update();
            } else {
                jdbc.sql("""
                        INSERT INTO handover_notes (note_key, note_value, updated_by) VALUES (?,?,?)
                        ON DUPLICATE KEY UPDATE note_value = VALUES(note_value), updated_by = VALUES(updated_by)""")
                        .params(change.getKey(), change.getValue(), actor.id()).update();
            }
        }
        if (!changes.isEmpty()) audit.recordDetails(actor.id(), "HANDOVER_UPDATED", "HANDOVER", null, Map.of("notes", List.copyOf(changes.keySet())));
        return notes();
    }
}
