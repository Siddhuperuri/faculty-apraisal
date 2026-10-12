package edu.svec.fams.auth;

import edu.svec.fams.audit.AuditService;
import edu.svec.fams.common.ApiException;
import edu.svec.fams.common.ValidationException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Forgot password": a person asks for a link, receives it by e-mail and chooses their own new password with it. Nothing
 * here reveals whether an address belongs to an account: a request always looks the same. The link carries a random token
 * whose SHA-256 alone is stored; it works once and for {@value #LIFETIME_MINUTES} minutes; a newer request replaces an
 * older link; a password is never e-mailed. Requests are rate limited by address and by e-mail.
 */
@Service
public class PasswordResetService {

    static final int LIFETIME_MINUTES = 30;
    static final int MAX_REQUESTS_PER_ADDRESS = 10;
    static final int MAX_REQUESTS_PER_EMAIL = 3;

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final AuditService audit;
    private final LoginRateLimiter limiter;
    private final SmtpMailer mailer;
    private final DefaultPassword defaultPassword;
    private final String publicUrl;
    private final boolean logLinks;

    public PasswordResetService(JdbcClient jdbc, PasswordEncoder encoder, AuditService audit, LoginRateLimiter limiter,
                                SmtpMailer mailer, DefaultPassword defaultPassword,
                                @Value("${FAMS_PUBLIC_URL:}") String publicUrl,
                                @Value("${FAMS_MAIL_LOG_LINKS:false}") boolean logLinks) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.audit = audit;
        this.limiter = limiter;
        this.mailer = mailer;
        this.defaultPassword = defaultPassword;
        this.publicUrl = publicUrl.strip().replaceAll("/+$", "");
        this.logLinks = logLinks;
    }

    private static String sha256(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Always completes the same way, whether or not the address has an account; the caller answers 202 either way. */
    public void request(String rawEmail, String address) {
        String email = rawEmail == null ? "" : rawEmail.strip().toLowerCase(Locale.ROOT);
        if (email.isEmpty() || email.length() > 190) return;
        if (!limiter.tryAcquire("reset|ip|" + address, MAX_REQUESTS_PER_ADDRESS).granted()
                || !limiter.tryAcquire("reset|email|" + email, MAX_REQUESTS_PER_EMAIL).granted()) {
            log.warn("Password reset requests are being refused from {}", address);
            return;
        }
        var user = jdbc.sql("SELECT id FROM users WHERE LOWER(email) = ? AND status = 'ACTIVE'").param(email)
                .query(Long.class).optional();
        if (user.isEmpty()) return;

        long userId = user.get();
        String token = issueToken(userId, address);
        audit.record(userId, "PASSWORD_RESET_REQUESTED", "USER", userId, (String) null);

        String link = publicUrl + "/reset-password?token=" + token;
        if (logLinks) log.info("Password reset link for user {}: {}", userId, link);
        if (mailer.configured() && !publicUrl.isEmpty()) {
            // sent in the background, so the answer takes as long for an unknown address as for a known one
            CompletableFuture.runAsync(() -> {
                try {
                    mailer.send(email, "Reset your Faculty Appraisal password",
                            "A request was made to reset the password of your Faculty Appraisal account.\n\n"
                                    + "Open this link to choose a new password. It works once and expires in " + LIFETIME_MINUTES
                                    + " minutes:\n\n" + link + "\n\nIf you did not ask for this, ignore this message; "
                                    + "your password has not changed.\n");
                } catch (java.io.IOException e) {
                    log.warn("The password reset e-mail could not be sent: {}", e.getMessage());
                }
            });
        } else {
            log.warn("A password reset was requested but mail is not configured (FAMS_MAIL_HOST, FAMS_MAIL_FROM, FAMS_PUBLIC_URL).");
        }
    }

    /**
     * A new one-time token for the user: any earlier unused link of theirs is ended, and only the SHA-256 of the new one is
     * stored. The caller delivers it.
     */
    private String issueToken(long userId, String requestedFrom) {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        jdbc.sql("UPDATE password_reset_tokens SET used_at = CURRENT_TIMESTAMP WHERE user_id = ? AND used_at IS NULL").param(userId).update();
        jdbc.sql("""
                INSERT INTO password_reset_tokens (user_id, token_hash, expires_at, requested_from)
                VALUES (?, ?, DATE_ADD(CURRENT_TIMESTAMP, INTERVAL ? MINUTE), ?)""")
                .params(userId, sha256(token), LIFETIME_MINUTES, requestedFrom).update();
        return token;
    }

    /** Whether a link can be e-mailed at all: a mail server and the address the link points to are both configured. */
    public boolean deliveryConfigured() {
        return mailer.configured() && !publicUrl.isEmpty();
    }

    /**
     * A reset link that an administrator sends to an active user. Unlike {@link #request}, the message is sent now, in the
     * caller's thread, so the caller knows whether the mail server accepted it. The link is the same one-time, 30 minute
     * link; it is ended again if the message could not be sent, so no usable link is left that nobody received. Nothing
     * here ever contains a password.
     *
     * @return true when the mail server accepted the message
     */
    public boolean sendLink(long userId, String email, long administratorId) {
        if (!deliveryConfigured()) return false;
        String token = issueToken(userId, "admin:" + administratorId);
        String link = publicUrl + "/reset-password?token=" + token;
        if (logLinks) log.info("Password reset link for user {}: {}", userId, link);
        try {
            mailer.send(email, "Choose a new Faculty Appraisal password",
                    "An administrator has asked for you to choose a new password for your Faculty Appraisal account.\n\n"
                            + "Open this link to choose it. It works once and expires in " + LIFETIME_MINUTES + " minutes:\n\n"
                            + link + "\n\nYour current password still works until you do. If you were not expecting this message, "
                            + "do not open the link; you can ignore it.\n");
            return true;
        } catch (java.io.IOException e) {
            log.warn("A password reset e-mail could not be sent to user {}: {}", userId, e.getMessage());
            jdbc.sql("UPDATE password_reset_tokens SET used_at = CURRENT_TIMESTAMP WHERE user_id = ? AND token_hash = ?")
                    .params(userId, sha256(token)).update();
            return false;
        }
    }

    /** Sets the new password for the person the link was issued to, and ends the link and every session of theirs. */
    @Transactional
    public void reset(String token, String newPassword) {
        if (token == null || token.length() < 20 || token.length() > 100) throw invalid();
        var row = jdbc.sql("""
                SELECT t.id, t.user_id, u.email FROM password_reset_tokens t JOIN users u ON u.id = t.user_id
                WHERE t.token_hash = ? AND t.used_at IS NULL AND t.expires_at > CURRENT_TIMESTAMP AND u.status = 'ACTIVE'
                FOR UPDATE""").param(sha256(token))
                .query((rs, n) -> new Object[] {rs.getLong(1), rs.getLong(2), rs.getString(3)}).optional().orElseThrow(PasswordResetService::invalid);
        if (newPassword == null || newPassword.getBytes(StandardCharsets.UTF_8).length > PasswordPolicy.MAX_BYTES) {
            throw new ValidationException(Map.of("newPassword", "Choose a password of at most " + PasswordPolicy.MAX_BYTES + " characters."));
        }
        String problem = defaultPassword.matches(newPassword) ? "Choose your own password, not the standard one you were given."
                : PasswordPolicy.problem(newPassword, (String) row[2]);
        if (problem != null) throw new ValidationException(Map.of("newPassword", problem));

        long userId = (Long) row[1];
        jdbc.sql("""
                UPDATE users SET password_hash = ?, must_change_password = FALSE,
                       session_version = session_version + 1, password_changed_at = CURRENT_TIMESTAMP
                WHERE id = ?""").params(encoder.encode(newPassword), userId).update();
        jdbc.sql("UPDATE password_reset_tokens SET used_at = CURRENT_TIMESTAMP WHERE user_id = ? AND used_at IS NULL").param(userId).update();
        audit.record(userId, "PASSWORD_RESET_BY_LINK", "USER", userId, (String) null);
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, "This link is not valid or has expired. Please ask for a new one.");
    }
}
