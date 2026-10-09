package edu.svec.fams.auth;

import edu.svec.fams.audit.AuditService;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Creates the first administrator so a fresh installation can be set up. Runs at start-up and only does anything when no
 * administrator exists and both FAMS_BOOTSTRAP_ADMIN_EMAIL and FAMS_BOOTSTRAP_ADMIN_PASSWORD are set. The account must
 * change that password at first sign-in. The password is never logged or audited; remove the variables afterwards.
 */
@Component
public class BootstrapAdmin implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(BootstrapAdmin.class);

    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final AuditService audit;
    private final String email;
    private final String password;

    public BootstrapAdmin(JdbcClient jdbc, PasswordEncoder encoder, AuditService audit,
                          @Value("${fams.bootstrap-admin-email:}") String email,
                          @Value("${fams.bootstrap-admin-password:}") String password) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.audit = audit;
        this.email = email.strip().toLowerCase(Locale.ROOT);
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (email.isEmpty() && password.isEmpty()) return;
        if (jdbc.sql("SELECT COUNT(*) FROM users WHERE role = 'ADMIN'").query(Integer.class).single() > 0) {
            log.info("Bootstrap administrator settings ignored: an administrator already exists. Remove them from the environment.");
            return;
        }
        if (email.isEmpty() || password.isEmpty()) {
            log.warn("Bootstrap administrator not created: both the e-mail and the password must be set.");
            return;
        }
        String problem = PasswordPolicy.problem(password, email);
        if (problem != null || !EmailAddress.isValid(email)) {
            log.warn("Bootstrap administrator not created: {}", problem != null ? "the password is not acceptable (" + problem + ")"
                    : "the e-mail address is not valid.");
            return;
        }
        int created = jdbc.sql("INSERT IGNORE INTO users (email, password_hash, role, must_change_password) VALUES (?,?, 'ADMIN', TRUE)")
                .params(email, encoder.encode(password)).update();
        if (created == 0) {
            log.warn("Bootstrap administrator not created: an account with that e-mail address already exists.");
            return;
        }
        long id = jdbc.sql("SELECT id FROM users WHERE email = ?").param(email).query(Long.class).single();
        audit.recordDetails(null, "ADMIN_BOOTSTRAPPED", "USER", id, Map.of("email", email));
        log.info("Bootstrap administrator created for {}. They must change the password at first sign-in.", email);
    }
}
