package edu.svec.fams.auth;

import edu.svec.fams.audit.AuditService;
import java.util.Arrays;
import java.util.List;
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
 * Sets every account's password back to the standard one ({@link DefaultPassword}) once, when the application starts with
 * {@code FAMS_RESET_ALL_PASSWORDS} set to a token. For when no administrator can do it from the console, for example on a
 * hosting service where the database cannot be reached.
 *
 * <p>The value is a token, not a switch, and each token works once: it is written to {@code startup_actions} before anything
 * changes, so a restart with the variable still set does nothing, and a person who has since chosen their own password
 * keeps it. To do it again, give a new token. Every account becomes the standard password, must choose a new one at its
 * next sign-in and loses its sessions; administrators included, so nobody stays signed in. Closed accounts of withdrawn
 * roles are left alone, and disabled accounts stay disabled. Unset the variable afterwards.
 *
 * <p>Nothing about the password reaches the log or the audit trail: only how many accounts were reset.
 */
@Component
public class StartupPasswordReset implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(StartupPasswordReset.class);

    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final DefaultPassword defaultPassword;
    private final AuditService audit;
    private final String token;

    public StartupPasswordReset(JdbcClient jdbc, PasswordEncoder encoder, DefaultPassword defaultPassword, AuditService audit,
                                @Value("${fams.reset-all-passwords:}") String token) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.defaultPassword = defaultPassword;
        this.audit = audit;
        this.token = token == null ? "" : token.strip();
    }

    @Override
    public void run(ApplicationArguments args) {
        if (token.isEmpty()) return;
        // Claimed first: only the instance that inserts the row goes on, and the same token never runs twice.
        int claimed = jdbc.sql("INSERT IGNORE INTO startup_actions (action) VALUES (?)")
                .param("reset-all-passwords:" + token).update();
        if (claimed == 0) {
            log.info("FAMS_RESET_ALL_PASSWORDS is set but that value was already used: nothing was reset. Remove the variable.");
            return;
        }
        List<String> roles = Arrays.stream(Role.values()).map(Enum::name).toList();
        List<Long> ids = jdbc.sql("SELECT id FROM users WHERE role IN (:roles) ORDER BY id").param("roles", roles)
                .query(Long.class).list();
        String hash = encoder.encode(defaultPassword.value());
        for (Long id : ids) {
            jdbc.sql("""
                    UPDATE users SET password_hash = ?, must_change_password = TRUE,
                           session_version = session_version + 1, password_changed_at = CURRENT_TIMESTAMP
                    WHERE id = ?""").params(hash, id).update();
            audit.recordDetails(null, "PASSWORD_RESET", "USER", id, Map.of("startup", true));
        }
        audit.recordDetails(null, "PASSWORDS_RESET_ALL", "USER", null, Map.of("accounts", ids.size()));
        log.warn("FAMS_RESET_ALL_PASSWORDS: the passwords of {} accounts were set back to the standard one. Remove the variable.", ids.size());
    }
}
