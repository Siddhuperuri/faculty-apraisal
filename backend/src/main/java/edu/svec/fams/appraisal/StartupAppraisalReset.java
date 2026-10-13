package edu.svec.fams.appraisal;

import edu.svec.fams.audit.AuditService;
import edu.svec.fams.documents.DocumentStorage;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Removes every appraisal, in every status, with everything entered in it, once, when the application starts with
 * {@code FAMS_RESET_APPRAISALS} set to a token not used before. For clearing the form data before go-live (or after a
 * rehearsal), when nobody can do it from the console or reach the database.
 *
 * <p>Removed: appraisals, every form section, scores, messages and their earlier wordings, the review history and the
 * issued official reports (the database rows and the stored files). Kept: accounts, faculty profiles, departments,
 * years, scoring policies, dropdown lists and the audit trail, which gets one entry saying how many appraisals went.
 *
 * <p>The history tables are append-only through triggers the application's own database user cannot drop, so the work is
 * done with the database-migration user (the one Flyway uses): the three "no delete" triggers are dropped, the rows are
 * deleted in one transaction, and the triggers are created again exactly as the migrations define them, whatever
 * happens. A token works once: it is recorded before anything is deleted, so a restart with the variable still set does
 * nothing. Unset the variable afterwards.
 */
@Component
public class StartupAppraisalReset implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(StartupAppraisalReset.class);

    /** Name and definition of each append-only trigger that blocks deleting, as V1, V5 and V21 create them. */
    private static final Map<String, String> TRIGGERS = new LinkedHashMap<>();

    static {
        TRIGGERS.put("review_actions_no_delete",
                "CREATE TRIGGER review_actions_no_delete BEFORE DELETE ON review_actions FOR EACH ROW "
                        + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'review_actions is append-only'");
        TRIGGERS.put("appraisal_reports_no_delete",
                "CREATE TRIGGER appraisal_reports_no_delete BEFORE DELETE ON appraisal_reports FOR EACH ROW "
                        + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'appraisal_reports is append-only'");
        TRIGGERS.put("appraisal_message_versions_no_delete",
                "CREATE TRIGGER appraisal_message_versions_no_delete BEFORE DELETE ON appraisal_message_versions FOR EACH ROW "
                        + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'appraisal_message_versions is append-only'");
    }

    /** Children of the appraisals table that do not delete with it, then the appraisals themselves, in a safe order. */
    private static final List<String> DELETE_ORDER = List.of(
            "appraisal_message_versions", "appraisal_messages", "review_actions", "appraisal_reports", "appraisal_scores", "appraisals");

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final DocumentStorage storage;
    private final String token;
    private final String url;
    private final String user;
    private final String password;

    public StartupAppraisalReset(JdbcClient jdbc, AuditService audit, DocumentStorage storage,
                                 @Value("${fams.reset-appraisals:}") String token,
                                 @Value("${spring.flyway.url:${spring.datasource.url}}") String url,
                                 @Value("${spring.flyway.user:${spring.datasource.username}}") String user,
                                 @Value("${spring.flyway.password:${spring.datasource.password:}}") String password) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.storage = storage;
        this.token = token == null ? "" : token.strip();
        this.url = url;
        this.user = user;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (token.isEmpty()) return;
        int claimed = jdbc.sql("INSERT IGNORE INTO startup_actions (action) VALUES (?)")
                .param("reset-appraisals:" + token).update();
        if (claimed == 0) {
            log.info("FAMS_RESET_APPRAISALS is set but that value was already used: nothing was removed. Remove the variable.");
            return;
        }

        Map<String, Integer> byStatus = new LinkedHashMap<>();
        jdbc.sql("SELECT status, COUNT(*) FROM appraisals GROUP BY status ORDER BY status")
                .query((rs, n) -> byStatus.put(rs.getString(1), rs.getInt(2))).list();
        List<String> reportFiles = jdbc.sql("SELECT storage_key FROM appraisal_reports").query(String.class).list();

        try {
            removeAll();
        } catch (SQLException e) {
            // Nothing was deleted (one transaction) and the triggers are back; the token is spent, so give a new one to retry.
            log.error("FAMS_RESET_APPRAISALS failed and removed nothing: {}", e.getMessage());
            throw new IllegalStateException("The appraisals could not be removed: " + e.getMessage(), e);
        }
        for (String key : reportFiles) storage.delete(key);

        int total = byStatus.values().stream().mapToInt(Integer::intValue).sum();
        Map<String, Object> details = new LinkedHashMap<>(byStatus);
        details.put("appraisals", total);
        details.put("reportFiles", reportFiles.size());
        audit.recordDetails(null, "APPRAISALS_RESET", "APPRAISAL", null, details);
        log.warn("FAMS_RESET_APPRAISALS: {} appraisals and {} issued reports were removed. Remove the variable.", total, reportFiles.size());
    }

    private void removeAll() throws SQLException {
        try (Connection c = DriverManager.getConnection(url, user, password)) {
            List<String> dropped = new ArrayList<>();
            try {
                c.setAutoCommit(true);
                try (Statement s = c.createStatement()) {
                    for (String name : TRIGGERS.keySet()) {
                        s.execute("DROP TRIGGER IF EXISTS " + name);
                        dropped.add(name);
                    }
                }
                c.setAutoCommit(false);
                try (Statement s = c.createStatement()) {
                    for (String table : DELETE_ORDER) s.executeUpdate("DELETE FROM " + table);
                    c.commit();
                } catch (SQLException e) {
                    c.rollback();
                    throw e;
                }
                c.setAutoCommit(true);
            } finally {
                restoreTriggers(c, dropped);
            }
        }
    }

    private static void restoreTriggers(Connection c, List<String> dropped) throws SQLException {
        c.setAutoCommit(true);
        try (Statement s = c.createStatement()) {
            for (String name : dropped) {
                try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA = DATABASE() "
                        + "AND TRIGGER_NAME = '" + name + "'")) {
                    rs.next();
                    if (rs.getInt(1) == 0) s.execute(TRIGGERS.get(name));
                }
            }
        }
    }
}
