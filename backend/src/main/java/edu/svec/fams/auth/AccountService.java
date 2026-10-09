package edu.svec.fams.auth;

import edu.svec.fams.audit.AuditService;
import edu.svec.fams.common.ApiException;
import edu.svec.fams.common.ValidationException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** What a signed-in person can do to their own account: see its state and change the password. */
@Service
public class AccountService {

    public record AccountState(boolean mustChangePassword) {}

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    /** Wrong guesses at the current password allowed in ten minutes (a stolen session must not be a way to find it). */
    static final int MAX_CURRENT_PASSWORD_GUESSES = 5;

    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final LoginRateLimiter rateLimiter;
    private final AuditService audit;
    private final DefaultPassword defaultPassword;

    public AccountService(JdbcClient jdbc, PasswordEncoder encoder, LoginRateLimiter rateLimiter, AuditService audit,
                          DefaultPassword defaultPassword) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.rateLimiter = rateLimiter;
        this.audit = audit;
        this.defaultPassword = defaultPassword;
    }

    public AccountState state(long userId) {
        return new AccountState(jdbc.sql("SELECT must_change_password FROM users WHERE id = ?").param(userId)
                .query(Boolean.class).optional().orElse(false));
    }

    /** What the Account page shows and lets the person edit. The administrator keeps no personal details here. */
    public record Details(boolean editable, String contactNo) {}

    public Details details(FamsUserPrincipal who) {
        if (who.role() == Role.ADMIN) return new Details(false, null);
        String sql = who.role() == Role.FACULTY
                ? "SELECT contact_no FROM faculty_profiles WHERE user_id = ?"
                : "SELECT contact_no FROM users WHERE id = ?";
        return new Details(true, jdbc.sql(sql).param(who.id()).query(String.class).optional().orElse(null));
    }

    /** Changes the caller's own contact number; blank clears it. Only the contact number is theirs to edit. */
    @Transactional
    public Details updateContact(FamsUserPrincipal who, String contactNo) {
        if (who.role() == Role.ADMIN) throw ApiException.forbidden("The administrator's account has no details to edit here.");
        String value = contactNo == null || contactNo.isBlank() ? null : contactNo.strip();
        if (value != null && !ContactNumber.isValid(value)) {
            throw new ValidationException(Map.of("contactNo", ContactNumber.MESSAGE));
        }
        int updated = who.role() == Role.FACULTY
                ? jdbc.sql("UPDATE faculty_profiles SET contact_no = ? WHERE user_id = ?").params(value, who.id()).update()
                : jdbc.sql("UPDATE users SET contact_no = ? WHERE id = ?").params(value, who.id()).update();
        if (updated == 0) throw ApiException.conflict("Your faculty profile has not been set up yet.");
        audit.record(who.id(), "CONTACT_UPDATED", "USER", who.id(), (String) null);   // the number itself is not written to the log
        return new Details(true, value);
    }

    public int sessionVersion(long userId) {
        return jdbc.sql("SELECT session_version FROM users WHERE id = ?").param(userId).query(Integer.class).single();
    }

    public void recordLogin(long userId) {
        jdbc.sql("UPDATE users SET last_login_at = CURRENT_TIMESTAMP WHERE id = ?").param(userId).update();
    }

    /**
     * Changes the caller's password. The current password is re-checked (and attempts are limited, so a stolen
     * session cannot be used to guess it). Every other session of this user ends; the caller keeps theirs by
     * adopting the returned session version.
     */
    @Transactional
    public int changePassword(FamsUserPrincipal who, String current, String next) {
        String key = "pw|" + who.id();
        // Counted before the check (and given back if the password is right), so a burst of parallel guesses
        // cannot all get through before the first one is counted.
        LoginRateLimiter.Outcome allowed = rateLimiter.tryAcquire(key, MAX_CURRENT_PASSWORD_GUESSES);
        if (!allowed.granted()) {
            if (allowed == LoginRateLimiter.Outcome.REFUSED) {
                log.warn("Password change for user {} is being refused: {} wrong current passwords in ten minutes",
                        who.id(), MAX_CURRENT_PASSWORD_GUESSES);
            }
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many incorrect attempts. Try again later.");
        }
        String hash = jdbc.sql("SELECT password_hash FROM users WHERE id = ? FOR UPDATE").param(who.id())
                .query(String.class).optional().orElseThrow(ApiException::notFound);
        if (current == null || current.getBytes(StandardCharsets.UTF_8).length > PasswordPolicy.MAX_BYTES
                || !encoder.matches(current, hash)) {
            log.warn("Password change refused for user {}: wrong current password", who.id());
            throw new ValidationException(Map.of("currentPassword", "Your current password is not correct."));
        }
        rateLimiter.reset(key);
        String problem = defaultPassword.matches(next) ? "Choose your own password, not the standard one you were given."
                : PasswordPolicy.problem(next, who.email());
        if (problem == null && encoder.matches(next, hash)) problem = "Choose a password you have not used just now.";
        if (problem != null) throw new ValidationException(Map.of("newPassword", problem));

        jdbc.sql("""
                UPDATE users SET password_hash = ?, must_change_password = FALSE,
                       session_version = session_version + 1, password_changed_at = CURRENT_TIMESTAMP
                WHERE id = ?""").params(encoder.encode(next), who.id()).update();
        audit.record(who.id(), "PASSWORD_CHANGED", "USER", who.id(), (String) null);
        return sessionVersion(who.id());
    }
}
