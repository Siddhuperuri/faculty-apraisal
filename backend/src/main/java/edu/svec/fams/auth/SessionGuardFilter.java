package edu.svec.fams.auth;

import edu.svec.fams.common.ErrorResponses;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Checks the signed-in user against the database on every request, so an administrator's decision takes effect
 * at once rather than at the next sign-in:
 * <ul>
 *   <li>a disabled account, or one whose role changed, is signed out (401);</li>
 *   <li>a session older than the last password change/reset is signed out (401).</li>
 * </ul>
 * A temporary or reset password never limits what the user may do; it is only a recommendation shown in the interface.
 * One primary-key lookup per request. Deliberately not a Spring bean: it is added to the security chain only.
 */
public final class SessionGuardFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(SessionGuardFilter.class);

    public static final String VERSION_ATTR = "FAMS_SESSION_VERSION";

    private record Row(String status, String role, int version) {}

    private final JdbcClient jdbc;

    public SessionGuardFilter(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof FamsUserPrincipal p) {
            Row row = jdbc.sql("SELECT status, role, session_version FROM users WHERE id = ?")
                    .param(p.id())
                    .query((rs, n) -> new Row(rs.getString(1), rs.getString(2), rs.getInt(3)))
                    .optional().orElse(null);

            HttpSession session = request.getSession(false);
            Object recorded = session == null ? null : session.getAttribute(VERSION_ATTR);
            boolean stale = recorded instanceof Integer v && row != null && v != row.version();

            if (row == null || !AccountStatus.isActive(row.status()) || !row.role().equals(p.role().name()) || stale) {
                log.info("Session ended for user {}: {}", p.id(), row == null ? "the account no longer exists"
                        : !AccountStatus.isActive(row.status()) ? "the account is disabled"
                        : stale ? "the password was changed or reset" : "the role changed");
                if (session != null) session.invalidate();
                SecurityContextHolder.clearContext();
                ErrorResponses.write(response, HttpStatus.UNAUTHORIZED, "Your session has ended. Please sign in again.");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
