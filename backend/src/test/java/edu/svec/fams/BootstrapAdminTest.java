package edu.svec.fams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.svec.fams.audit.AuditService;
import edu.svec.fams.auth.BootstrapAdmin;
import edu.svec.fams.auth.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class BootstrapAdminTest {

    @Autowired JdbcClient jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired AuditService audit;
    @Autowired TestDb db;

    @BeforeEach
    void clean() { db.reset(); }

    private void run(String email, String password) {
        new BootstrapAdmin(jdbc, encoder, audit, email, password).run(null);
    }

    private int admins() {
        return jdbc.sql("SELECT COUNT(*) FROM users WHERE role = 'ADMIN'").query(Integer.class).single();
    }

    @Test
    void createsTheFirstAdministratorWhoMustChangeThePassword() {
        run("  First.Admin@Svec.edu ", "starter-pass-2026");
        assertEquals(1, admins());
        var row = jdbc.sql("SELECT email, password_hash, status, must_change_password FROM users WHERE role = 'ADMIN'").query().singleRow();
        assertEquals("first.admin@svec.edu", row.get("email"));
        assertEquals("ACTIVE", row.get("status"));
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM users WHERE role = 'ADMIN' AND must_change_password = TRUE").query(Integer.class).single());
        String hash = (String) row.get("password_hash");
        assertTrue(encoder.matches("starter-pass-2026", hash));
        assertFalse(hash.contains("starter-pass-2026"));
        // recorded, without the password
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM audit_logs WHERE action = 'ADMIN_BOOTSTRAPPED'").query(Integer.class).single());
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM audit_logs WHERE CAST(metadata AS CHAR) LIKE '%starter-pass%'").query(Integer.class).single());
    }

    @Test
    void doesNothingWhenAnAdministratorAlreadyExists() {
        db.user("existing@svec.edu", Role.ADMIN);
        run("second@svec.edu", "starter-pass-2026");
        assertEquals(1, admins());
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM users WHERE email = 'second@svec.edu'").query(Integer.class).single());
    }

    @Test
    void doesNothingWithoutBothSettings() {
        run("", "");
        run("only.email@svec.edu", "");
        run("", "starter-pass-2026");
        assertEquals(0, admins());
    }

    @Test
    void refusesAnUnacceptablePasswordOrEmail() {
        run("weak@svec.edu", "short1");
        run("weak@svec.edu", "nodigitsatall");
        run("weak@svec.edu", "weak-pass-2026");        // contains the e-mail's name
        run("not-an-email", "starter-pass-2026");
        assertEquals(0, admins());
    }

    @Test
    void doesNotTakeOverAnExistingAccountWithTheSameEmail() {
        db.user("taken@svec.edu", Role.FACULTY);
        run("taken@svec.edu", "starter-pass-2026");
        assertEquals(0, admins());
        assertEquals("FACULTY", jdbc.sql("SELECT role FROM users WHERE email = 'taken@svec.edu'").query(String.class).single());
    }

    @Test
    void runningTwiceCreatesOnlyOne() {
        run("first@svec.edu", "starter-pass-2026");
        run("first@svec.edu", "starter-pass-2026");
        assertEquals(1, admins());
    }
}
