package edu.svec.fams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.svec.fams.audit.AuditService;
import edu.svec.fams.auth.DefaultPassword;
import edu.svec.fams.auth.Role;
import edu.svec.fams.auth.StartupPasswordReset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/** FAMS_RESET_ALL_PASSWORDS: every account back to the standard password, once per token. */
@SpringBootTest
@ActiveProfiles("test")
class StartupPasswordResetTest {

    @Autowired TestDb db;
    @Autowired JdbcClient jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired DefaultPassword defaultPassword;
    @Autowired AuditService audit;

    long faculty, admin;

    @BeforeEach
    void setUp() {
        db.reset();
        faculty = db.faculty("f@test.edu", "E1", "CSE", "ASST_PROF").id();
        admin = db.user("admin@test.edu", Role.ADMIN).id();
        // both have chosen a password of their own
        for (long id : new long[] {faculty, admin}) {
            jdbc.sql("UPDATE users SET password_hash = ?, must_change_password = FALSE WHERE id = ?")
                    .params(encoder.encode("Their-Own-Pass-1"), id).update();
        }
    }

    private void run(String token) {
        new StartupPasswordReset(jdbc, encoder, defaultPassword, audit, token).run(null);
    }

    private boolean standard(long id) {
        String hash = jdbc.sql("SELECT password_hash FROM users WHERE id = ?").param(id).query(String.class).single();
        return encoder.matches(defaultPassword.value(), hash);
    }

    private boolean mustChange(long id) {
        return jdbc.sql("SELECT must_change_password FROM users WHERE id = ?").param(id).query(Boolean.class).single();
    }

    @Test
    void nothingHappensWithoutAToken() {
        run("");
        run("   ");
        assertFalse(standard(faculty));
        assertFalse(standard(admin));
    }

    @Test
    void everyAccountBecomesTheStandardPasswordMustBeReplacedAndLosesItsSessions() {
        int before = jdbc.sql("SELECT session_version FROM users WHERE id = ?").param(faculty).query(Integer.class).single();
        run("go-" + UUID.randomUUID());
        for (long id : new long[] {faculty, admin}) {
            assertTrue(standard(id), "account " + id);
            assertTrue(mustChange(id), "account " + id);
        }
        assertEquals(before + 1, jdbc.sql("SELECT session_version FROM users WHERE id = ?").param(faculty).query(Integer.class).single());
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM audit_logs WHERE action = 'PASSWORDS_RESET_ALL'").query(Integer.class).single());
        assertEquals(2, jdbc.sql("SELECT COUNT(*) FROM audit_logs WHERE action = 'PASSWORD_RESET'").query(Integer.class).single());
        for (String meta : jdbc.sql("SELECT COALESCE(CAST(metadata AS CHAR), '') FROM audit_logs").query(String.class).list()) {
            assertFalse(meta.contains(defaultPassword.value()) || meta.contains("$2a$"), meta);
        }
    }

    @Test
    void theSameTokenNeverRunsTwiceSoARestartKeepsWhatPeopleChose() {
        String token = "once-" + UUID.randomUUID();
        run(token);
        jdbc.sql("UPDATE users SET password_hash = ?, must_change_password = FALSE WHERE id = ?")
                .params(encoder.encode("Chosen-After-Reset-2"), faculty).update();
        run(token);                                  // the application restarted with the variable still set
        assertFalse(standard(faculty));
        assertFalse(mustChange(faculty));
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM audit_logs WHERE action = 'PASSWORDS_RESET_ALL'").query(Integer.class).single());

        run("again-" + UUID.randomUUID());           // a new token is a new decision
        assertTrue(standard(faculty));
    }

    @Test
    void closedAccountsOfWithdrawnRolesAreLeftAlone() {
        jdbc.sql("INSERT INTO users (email, password_hash, role, status) VALUES ('dean@test.edu', ?, 'DEAN', 'DISABLED')")
                .param(encoder.encode("Their-Own-Pass-1")).update();
        long dean = jdbc.sql("SELECT id FROM users WHERE email = 'dean@test.edu'").query(Long.class).single();
        run("go-" + UUID.randomUUID());
        assertFalse(standard(dean));
        assertTrue(standard(faculty));
    }
}
