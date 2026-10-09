package edu.svec.fams.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Append-only audit trail. No update or delete paths exist. */
@Service
public class AuditService {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public AuditService(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** @param metadataJson a JSON document, or null */
    public void record(Long actorId, String action, String entityType, Long entityId, String metadataJson) {
        jdbc.sql("INSERT INTO audit_logs (actor_id, action, entity_type, entity_id, metadata) VALUES (?,?,?,?,?)")
                .params(actorId, action, entityType, entityId, metadataJson)
                .update();
    }

    /** Same, with the metadata given as a map (serialised safely, so names and text cannot break the JSON). */
    public void recordDetails(Long actorId, String action, String entityType, Long entityId, Map<String, ?> metadata) {
        try {
            record(actorId, action, entityType, entityId, metadata == null ? null : json.writeValueAsString(metadata));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Audit metadata could not be serialised", e);
        }
    }
}
