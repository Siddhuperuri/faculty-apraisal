package edu.svec.fams.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Limits password guessing at sign-in. Three counters, each over the same ten-minute window; only attempts whose
 * password was actually checked and found wrong stay counted:
 * <ul>
 *   <li><b>account + client address</b> ({@value #PER_ACCOUNT_AND_ADDRESS}): the everyday limit. Because the address is
 *       part of the key, one person guessing at the Principal's account locks out only themselves;</li>
 *   <li><b>account, from any address</b> ({@value #PER_ACCOUNT}): on a LAN an address is cheap to change, and a forwarding
 *       header can be forged where the proxy set-up is wrong, so the first limit alone could be walked around.
 *       This one cannot. It does not apply to the address the account last signed in from successfully, so the real
 *       owner at their usual machine is not shut out by someone else's guessing;</li>
 *   <li><b>client address, any account</b> ({@value #PER_ADDRESS}): stops one machine trying a few common passwords
 *       against every account in turn, and bounds the password-hashing work one client can demand.</li>
 * </ul>
 * The response is the same whichever limit refused, so it says nothing about which accounts exist.
 *
 * <p>Nothing is kept in this server's memory: the counters are in the database ({@link LoginRateLimiter}) and so is the
 * address each account last signed in from ({@code users.last_login_address}), so several instances of the backend
 * enforce one set of limits and a restart forgets nothing.
 */
@Component
public class SignInThrottle {
    private static final Logger log = LoggerFactory.getLogger(SignInThrottle.class);

    static final int PER_ACCOUNT_AND_ADDRESS = 5;
    static final int PER_ACCOUNT = 25;
    static final int PER_ADDRESS = 100;

    /** What {@link #begin} counted, so the outcome can be settled against exactly those counters. */
    public record Attempt(String email, String address, String pairKey, String accountKey, String addressKey) {}

    /** Longest client address kept as an account's usual one (users.last_login_address). */
    private static final int MAX_ADDRESS_LENGTH = 64;

    private final LoginRateLimiter limiter;
    private final JdbcClient jdbc;

    public SignInThrottle(LoginRateLimiter limiter, JdbcClient jdbc) {
        this.limiter = limiter;
        this.jdbc = jdbc;
    }

    /**
     * Counts an attempt that is about to check a password.
     *
     * @return the attempt to settle with {@link #succeeded} or {@link #abandoned} (a wrong password needs nothing
     *         more: it stays counted), or null if the attempt must be refused without checking the password
     */
    public Attempt begin(String email, String address) {
        String addressKey = "addr|" + address;
        String pairKey = "pair|" + email + "|" + address;
        String accountKey = address.equals(usualAddress(email)) ? null : "acct|" + email;

        LoginRateLimiter.Outcome byAddress = limiter.tryAcquire(addressKey, PER_ADDRESS);
        if (!byAddress.granted()) {
            if (byAddress == LoginRateLimiter.Outcome.REFUSED) {
                log.warn("Sign-in attempts from {} are being refused: {} failures in ten minutes across accounts", address, PER_ADDRESS);
            }
            return null;
        }
        // A refused attempt checks no password, so it must not use up the limits that come after it; otherwise
        // hammering one limit would be a way to trip the others.
        LoginRateLimiter.Outcome byPair = limiter.tryAcquire(pairKey, PER_ACCOUNT_AND_ADDRESS);
        if (!byPair.granted()) {
            limiter.release(addressKey);
            if (byPair == LoginRateLimiter.Outcome.REFUSED) {
                log.warn("Sign-in attempts for {} from {} are being refused: {} failures in ten minutes",
                        AuthController.loggable(email), address, PER_ACCOUNT_AND_ADDRESS);
            }
            return null;
        }
        if (accountKey != null) {
            LoginRateLimiter.Outcome byAccount = limiter.tryAcquire(accountKey, PER_ACCOUNT);
            if (!byAccount.granted()) {
                limiter.release(pairKey);
                limiter.release(addressKey);
                if (byAccount == LoginRateLimiter.Outcome.REFUSED) {
                    log.warn("Sign-in attempts for {} are being refused from every new address: {} failures in ten minutes "
                            + "from several addresses (possible distributed guessing)", AuthController.loggable(email), PER_ACCOUNT);
                }
                return null;
            }
        }
        return new Attempt(email, address, pairKey, accountKey, addressKey);
    }

    /** The password was right: forget this account's failures, and remember where its owner signs in from. */
    public void succeeded(Attempt a) {
        limiter.reset(a.pairKey());
        if (a.accountKey() != null) limiter.reset(a.accountKey());
        // The address counter spans accounts, so one success must not wipe it: a person with an account of their own
        // could otherwise sign in between rounds of guessing at other people's. Only this attempt is given back.
        limiter.release(a.addressKey());
        if (a.address().length() <= MAX_ADDRESS_LENGTH) {
            jdbc.sql("UPDATE users SET last_login_address = ? WHERE email = ?").params(a.address(), a.email()).update();
        }
    }

    /**
     * The address this account last signed in from successfully, or null. Only the account spelled exactly as typed
     * counts (the database's own comparison ignores accents and letter width, see {@link FamsUserDetailsService}).
     */
    private String usualAddress(String email) {
        return jdbc.sql("SELECT email, last_login_address FROM users WHERE email = ?").param(email)
                .query((rs, n) -> new String[] {rs.getString(1), rs.getString(2)}).optional()
                .filter(row -> row[0].equals(email))
                .map(row -> row[1]).orElse(null);
    }

    /** No password was checked after all (for example the database was unreachable): nothing should stay counted. */
    public void abandoned(Attempt a) {
        limiter.release(a.pairKey());
        if (a.accountKey() != null) limiter.release(a.accountKey());
        limiter.release(a.addressKey());
    }
}
