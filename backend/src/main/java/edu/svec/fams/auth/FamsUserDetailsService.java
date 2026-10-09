package edu.svec.fams.auth;

import java.util.Locale;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class FamsUserDetailsService implements UserDetailsService {
    private record Row(long id, String email, String passwordHash, String role, String status) {}

    private final JdbcClient jdbc;

    public FamsUserDetailsService(JdbcClient jdbc) { this.jdbc = jdbc; }

    /**
     * Finds the account for an e-mail address exactly as it is stored, apart from letter case.
     *
     * <p>The database's own comparison is looser than that: it also ignores accents and letter width, so an accented
     * or full-width spelling of "principal@..." would find "principal@...". If those spellings could sign in, each
     * would be a different key to the sign-in limits (which count per address typed), and the limit on guessing at one
     * account could be walked around with a supply of spellings. So the row the database returns is accepted only if
     * its address, lower-cased, is character for character the one that was typed. (Comparing with
     * {@code equalsIgnoreCase} would not do: it treats the long s and the Kelvin sign as "s" and "k".)
     */
    @Override
    public UserDetails loadUserByUsername(String email) {
        String wanted = email.toLowerCase(Locale.ROOT);
        return jdbc.sql("SELECT id, email, password_hash, role, status FROM users WHERE email = ?")
                .param(wanted)
                .query((rs, n) -> new Row(rs.getLong("id"), rs.getString("email"), rs.getString("password_hash"),
                        rs.getString("role"), rs.getString("status")))
                .optional()
                .filter(r -> r.email().toLowerCase(Locale.ROOT).equals(wanted))
                .filter(r -> r.passwordHash() != null)
                // An account whose role has been withdrawn (Dean, Vice Principal) cannot sign in at all.
                .flatMap(r -> Role.find(r.role()).map(role ->
                        new FamsUserPrincipal(r.id(), r.email(), r.passwordHash(), role, AccountStatus.isActive(r.status()))))
                .map(UserDetails.class::cast)
                .orElseThrow(() -> new UsernameNotFoundException("unknown user"));
    }
}
