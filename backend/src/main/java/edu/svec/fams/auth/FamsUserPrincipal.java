package edu.svec.fams.auth;

import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * The authenticated user. The password hash is only present while authenticating; the copy kept in the
 * HTTP session is created with {@link #withoutPassword()}. Role and active flag are snapshotted at login, but
 * {@link SessionGuardFilter} re-checks them against the database on every request, so an administrator's
 * change (disable, password reset) ends the session at once.
 */
public record FamsUserPrincipal(Long id, String email, String passwordHash, Role role, boolean active)
        implements UserDetails {

    public FamsUserPrincipal withoutPassword() {
        return new FamsUserPrincipal(id, email, null, role, active);
    }

    @Override public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }
    @Override public String getPassword() { return passwordHash; }
    @Override public String getUsername() { return email; }
    @Override public boolean isEnabled() { return active; }

    /** Never print the hash (records generate a toString that includes every component). */
    @Override public String toString() {
        return "FamsUserPrincipal[id=" + id + ", role=" + role + "]";
    }
}
