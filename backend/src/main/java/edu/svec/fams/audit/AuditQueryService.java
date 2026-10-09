package edu.svec.fams.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.svec.fams.common.ApiException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only view of the append-only audit trail, newest first, in pages. */
@Service
public class AuditQueryService {

    public record Entry(long id, OffsetDateTime at, Long actorId, String actorEmail, String action, String entityType,
                        Long entityId, JsonNode details) {}

    public record Page(List<Entry> items, int page, int size, long total) {}

    public static final int MAX_PAGE_SIZE = 100;

    /**
     * Administrators do not read appraisal content, so entries about an appraisal show only these counts, ids and
     * categories. The stored entry is complete (file names, report checksums); it is this view that leaves them out.
     */
    private static final Set<String> APPRAISAL_DETAILS = Set.of("section", "changes", "documentId", "category", "size", "bytes",
            "from", "to");

    private static final Logger log = LoggerFactory.getLogger(AuditQueryService.class);

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public AuditQueryService(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public Page query(int page, int size, String action, String entityType, Long entityId) {
        if (page < 0) throw ApiException.badRequest("Page must be 0 or more.");
        if (size < 1 || size > MAX_PAGE_SIZE) throw ApiException.badRequest("Page size must be between 1 and " + MAX_PAGE_SIZE + ".");
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (action != null && !action.isBlank()) { where.append(" AND l.action = ?"); params.add(action.strip()); }
        if (entityType != null && !entityType.isBlank()) { where.append(" AND l.entity_type = ?"); params.add(entityType.strip()); }
        if (entityId != null) { where.append(" AND l.entity_id = ?"); params.add(entityId); }

        long total = jdbc.sql("SELECT COUNT(*) FROM audit_logs l" + where).params(params).query(Long.class).single();
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(size);
        pageParams.add((long) page * size);
        List<Entry> items = jdbc.sql("""
                SELECT l.id, l.created_at, l.actor_id, u.email, l.action, l.entity_type, l.entity_id, l.metadata
                FROM audit_logs l LEFT JOIN users u ON u.id = l.actor_id""" + where + " ORDER BY l.id DESC LIMIT ? OFFSET ?")
                .params(pageParams)
                .query((rs, n) -> new Entry(rs.getLong(1), rs.getObject(2, OffsetDateTime.class),
                        rs.getObject(3, Long.class), rs.getString(4), rs.getString(5), rs.getString(6),
                        rs.getObject(7, Long.class), parse(rs.getString(8), "APPRAISAL".equals(rs.getString(6)))))
                .list();
        return new Page(items, page, size, total);
    }

    /**
     * Deletes the entries matching the filters (all of them when there are none), or the one entry with {@code id}, and
     * records that it was done: one new AUDIT_DELETED entry naming the administrator and the number removed.
     */
    @Transactional
    public int delete(long actorId, Long id, String action, String entityType, Long entityId) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (id != null) { where.append(" AND id = ?"); params.add(id); }
        if (action != null && !action.isBlank()) { where.append(" AND action = ?"); params.add(action.strip()); }
        if (entityType != null && !entityType.isBlank()) { where.append(" AND entity_type = ?"); params.add(entityType.strip()); }
        if (entityId != null) { where.append(" AND entity_id = ?"); params.add(entityId); }
        int removed = jdbc.sql("DELETE FROM audit_logs" + where).params(params).update();
        if (id != null && removed == 0) throw ApiException.notFound();
        jdbc.sql("INSERT INTO audit_logs (actor_id, action, entity_type, entity_id, metadata) VALUES (?,?,?,?,?)")
                .params(actorId, "AUDIT_DELETED", "AUDIT", id, "{\"removed\":" + removed + "}").update();
        return removed;
    }

    private JsonNode parse(String text, boolean appraisalEntry) {
        if (text == null) return null;
        try {
            JsonNode node = json.readTree(text);
            if (appraisalEntry && node instanceof ObjectNode object) object.retain(APPRAISAL_DETAILS);
            return node;
        } catch (JsonProcessingException e) {
            log.warn("An audit entry's details are not valid JSON and are shown as empty", e);
            return null;
        }
    }
}
