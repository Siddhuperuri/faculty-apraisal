package edu.svec.fams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.svec.fams.appraisal.AppraisalService;
import edu.svec.fams.appraisal.StartupAppraisalReset;
import edu.svec.fams.audit.AuditService;
import edu.svec.fams.auth.FamsUserPrincipal;
import edu.svec.fams.documents.DocumentStorage;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/** FAMS_RESET_APPRAISALS: every appraisal and its form data removed once per token; everything else stays. */
@SpringBootTest
@ActiveProfiles("test")
class StartupAppraisalResetTest {

    @Autowired TestDb db;
    @Autowired JdbcClient jdbc;
    @Autowired AppraisalService appraisals;
    @Autowired AuditService audit;
    @Autowired DocumentStorage storage;
    @Value("${spring.datasource.url}") String url;
    @Value("${spring.datasource.username}") String user;
    @Value("${spring.datasource.password:}") String password;

    FamsUserPrincipal faculty, other, hod;

    @BeforeEach
    void setUp() {
        db.reset();
        faculty = db.faculty("f@test.edu", "E1", "CSE", "ASST_PROF");
        other = db.faculty("g@test.edu", "E2", "CSE", "PROFESSOR");
        hod = db.hod("hod@test.edu", "CSE");
        // one draft with entries, one submitted with a review step, a message and its earlier wording, and an issued report
        long draft = appraisals.create(faculty);
        jdbc.sql("INSERT INTO teaching_courses (appraisal_id, course_code, course_name, course_type, program, branch, semester, hours_per_week, pass_percentage)"
                + " VALUES (?, 'C1', 'Intro', 'THEORY', 'B_TECH', 'CSE', 3, 4, 90)").param(draft).update();
        jdbc.sql("INSERT INTO action_plan (appraisal_id, plan_text) VALUES (?, 'Plan')").param(draft).update();
        long done = appraisals.create(other);
        db.complete(done);
        appraisals.submit(done, other);
        long message = insertMessage(done);
        jdbc.sql("INSERT INTO appraisal_message_versions (message_id, body, written_at) VALUES (?, 'earlier', CURRENT_TIMESTAMP)").param(message).update();
        jdbc.sql("INSERT INTO appraisal_reports (appraisal_id, storage_key, checksum, size_bytes) VALUES (?, ?, REPEAT('a', 64), 10)")
                .params(done, "key-" + UUID.randomUUID()).update();
    }

    private long insertMessage(long appraisal) {
        jdbc.sql("INSERT INTO appraisal_messages (appraisal_id, sender_id, sender_role, body) VALUES (?, ?, 'HOD', 'Please add the lab results.')")
                .params(appraisal, hod.id()).update();
        return jdbc.sql("SELECT MAX(id) FROM appraisal_messages").query(Long.class).single();
    }

    private StartupAppraisalReset runner(String token) {
        return new StartupAppraisalReset(jdbc, audit, storage, token, url, user, password);
    }

    private int count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single();
    }

    private List<String> triggers() {
        return new ArrayList<>(jdbc.sql("SELECT TRIGGER_NAME FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA = DATABASE() "
                + "AND TRIGGER_NAME LIKE '%no_delete' ORDER BY 1").query(String.class).list());
    }

    @Test
    void nothingHappensWithoutAToken() {
        runner("").run(null);
        runner("   ").run(null);
        assertEquals(2, count("appraisals"));
    }

    @Test
    void everyAppraisalAndItsDataGoesButAccountsAndTheAuditTrailStay() {
        List<String> before = triggers();
        assertTrue(before.contains("review_actions_no_delete"));
        assertEquals(1, count("appraisal_reports"));
        int audits = count("audit_logs");

        runner("wipe-" + UUID.randomUUID()).run(null);

        for (String table : new String[] {"appraisals", "teaching_courses", "action_plan", "general_information", "appraisal_scores",
                "review_actions", "appraisal_messages", "appraisal_message_versions", "appraisal_reports"}) {
            assertEquals(0, count(table), table);
        }
        assertEquals(3, count("users"));                   // accounts untouched
        assertEquals(2, count("faculty_profiles"));
        assertTrue(count("audit_logs") > audits, "the audit trail is kept and gains an entry");
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM audit_logs WHERE action = 'APPRAISALS_RESET'").query(Integer.class).single());
        assertEquals(before, triggers(), "the append-only triggers are back, exactly as they were");
    }

    @Test
    void aNewAppraisalCanBeStartedAfterwardsAndTheSameTokenNeverRunsTwice() {
        String token = "once-" + UUID.randomUUID();
        runner(token).run(null);
        long fresh = appraisals.create(faculty);
        runner(token).run(null);                           // a restart with the variable still set
        assertEquals(1, count("appraisals"));
        assertEquals(fresh, jdbc.sql("SELECT id FROM appraisals").query(Long.class).single());
        runner("again-" + UUID.randomUUID()).run(null);    // a new token is a new decision
        assertEquals(0, count("appraisals"));
    }

    @Test
    void theHistoryTablesAreStillAppendOnlyAfterTheReset() {
        runner("t-" + UUID.randomUUID()).run(null);
        long a = appraisals.create(other);
        db.complete(a);
        appraisals.submit(a, other);
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataAccessException.class,
                () -> jdbc.sql("DELETE FROM review_actions").update());
    }
}
