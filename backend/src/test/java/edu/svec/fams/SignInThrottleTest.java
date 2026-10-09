package edu.svec.fams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import edu.svec.fams.auth.LoginRateLimiter;
import edu.svec.fams.auth.Role;
import edu.svec.fams.auth.SignInThrottle;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/**
 * The three sign-in limits and how they interact. A wrong password is simply an attempt that is never settled.
 * Everything is kept in the database, so the tests also start a second instance and restart the first.
 */
@SpringBootTest
@ActiveProfiles("test")
class SignInThrottleTest {

    @Autowired JdbcClient jdbc;
    @Autowired TestDb db;

    private final LoginRateLimiterTest.MutableClock clock = new LoginRateLimiterTest.MutableClock();
    private SignInThrottle throttle;

    @BeforeEach
    void setUp() {
        db.reset();
        db.user("principal@svec.edu", Role.PRINCIPAL);
        db.user("a@svec.edu", Role.FACULTY);
        throttle = instance();
    }

    /** Another instance of the backend (or the same one after a restart): nothing carried over in memory. */
    private SignInThrottle instance() { return new SignInThrottle(new LoginRateLimiter(jdbc, clock), jdbc); }

    private void fail(String email, String address, int times) {
        for (int i = 0; i < times; i++) assertNotNull(throttle.begin(email, address), "attempt " + (i + 1) + " from " + address);
    }

    @Test
    void fiveFailuresFromOneAddressBlockThatAddressOnlyForThatAccount() {
        fail("principal@svec.edu", "10.0.0.5", 5);
        assertNull(throttle.begin("principal@svec.edu", "10.0.0.5"));
        // Someone else's guessing does not shut the owner out at another machine, nor other accounts at this one.
        assertNotNull(throttle.begin("principal@svec.edu", "10.0.0.9"));
        assertNotNull(throttle.begin("hod@svec.edu", "10.0.0.5"));
    }

    @Test
    void changingAddressDoesNotBuyUnlimitedGuessesAtOneAccount() {
        // 25 wrong passwords, five from each of five addresses (or five forged forwarding headers).
        for (int a = 1; a <= 5; a++) fail("principal@svec.edu", "10.0.1." + a, 5);
        // A sixth address is refused although it has never been seen.
        assertNull(throttle.begin("principal@svec.edu", "10.0.1.6"));
        assertNull(throttle.begin("principal@svec.edu", "10.0.1.7"));
    }

    @Test
    void theOwnersUsualMachineIsNotShutOutByOtherPeoplesGuessing() {
        SignInThrottle.Attempt ok = throttle.begin("principal@svec.edu", "10.0.9.9");
        throttle.succeeded(ok);

        for (int a = 1; a <= 5; a++) fail("principal@svec.edu", "10.0.2." + a, 5);
        assertNull(throttle.begin("principal@svec.edu", "10.0.2.6"));          // the account is closed to new addresses

        SignInThrottle.Attempt again = throttle.begin("principal@svec.edu", "10.0.9.9");
        assertNotNull(again, "the address the Principal last signed in from still works");
        throttle.succeeded(again);
        // ...and that success did not reopen the account to the addresses that were guessing.
        assertNull(throttle.begin("principal@svec.edu", "10.0.2.6"));
    }

    @Test
    void theUsualMachineStillHasTheEverydayLimit() {
        throttle.succeeded(throttle.begin("principal@svec.edu", "10.0.9.9"));
        fail("principal@svec.edu", "10.0.9.9", 5);
        assertNull(throttle.begin("principal@svec.edu", "10.0.9.9"));
    }

    @Test
    void refusedAttemptsDoNotCountTowardsTheAccountLimit() {
        // One address hammering away gets five real attempts; the rest are refused and must not close the account.
        fail("principal@svec.edu", "10.0.3.1", 5);
        for (int i = 0; i < 60; i++) assertNull(throttle.begin("principal@svec.edu", "10.0.3.1"));
        assertNotNull(throttle.begin("principal@svec.edu", "10.0.3.2"));
    }

    @Test
    void oneAddressCannotTryAFewPasswordsAgainstEveryAccount() {
        for (int u = 0; u < 100; u++) fail("user" + u + "@svec.edu", "10.0.4.1", 1);
        assertNull(throttle.begin("user100@svec.edu", "10.0.4.1"));
        assertNotNull(throttle.begin("user100@svec.edu", "10.0.4.2"));           // other machines are unaffected
    }

    @Test
    void refusedAttemptsDoNotCountTowardsTheAddressLimit() {
        fail("a@svec.edu", "10.0.5.1", 5);
        for (int i = 0; i < 120; i++) assertNull(throttle.begin("a@svec.edu", "10.0.5.1"));
        assertNotNull(throttle.begin("b@svec.edu", "10.0.5.1"));
    }

    @Test
    void aSuccessClearsTheAccountsFailuresButNotTheAddressCount() {
        fail("a@svec.edu", "10.0.6.1", 4);
        throttle.succeeded(throttle.begin("a@svec.edu", "10.0.6.1"));
        fail("a@svec.edu", "10.0.6.1", 5);                                       // a fresh five after signing in

        // Signing in to one's own account between rounds does not reset what this address has done to others:
        // 4 + 5 failures above, so 91 more reach the limit of 100.
        for (int u = 0; u < 91; u++) fail("victim" + u + "@svec.edu", "10.0.6.1", 1);
        assertNull(throttle.begin("victim-next@svec.edu", "10.0.6.1"));
    }

    @Test
    void anAbandonedAttemptLeavesNoTrace() {
        // For example the database was down: no password was checked, so nothing may stay counted.
        for (int i = 0; i < 20; i++) throttle.abandoned(throttle.begin("a@svec.edu", "10.0.7.1"));
        fail("a@svec.edu", "10.0.7.1", 5);
        assertNull(throttle.begin("a@svec.edu", "10.0.7.1"));
    }

    @Test
    void everythingClearsAfterTheWindow() {
        for (int a = 1; a <= 5; a++) fail("principal@svec.edu", "10.0.8." + a, 5);
        assertNull(throttle.begin("principal@svec.edu", "10.0.8.6"));
        clock.advance(Duration.ofMinutes(11));
        assertNotNull(throttle.begin("principal@svec.edu", "10.0.8.6"));
        assertNotNull(throttle.begin("principal@svec.edu", "10.0.8.1"));
    }

    // ---- shared across instances and restarts ----

    @Test
    void theLimitsAreOneSetForEveryInstanceAndSurviveARestart() {
        SignInThrottle other = instance();
        // three wrong passwords on one instance, two on another: the sixth is refused whichever it reaches
        fail("principal@svec.edu", "10.0.10.1", 3);
        assertNotNull(other.begin("principal@svec.edu", "10.0.10.1"));
        assertNotNull(other.begin("principal@svec.edu", "10.0.10.1"));
        assertNull(throttle.begin("principal@svec.edu", "10.0.10.1"));
        assertNull(other.begin("principal@svec.edu", "10.0.10.1"));
        // a restart does not hand the guesser a fresh five
        assertNull(instance().begin("principal@svec.edu", "10.0.10.1"));
    }

    @Test
    void theOwnersUsualMachineIsRememberedAcrossInstancesAndRestarts() {
        throttle.succeeded(throttle.begin("principal@svec.edu", "10.0.9.9"));
        assertEquals("10.0.9.9", jdbc.sql("SELECT last_login_address FROM users WHERE email = 'principal@svec.edu'").query(String.class).single());

        for (int a = 1; a <= 5; a++) fail("principal@svec.edu", "10.0.11." + a, 5);
        SignInThrottle restarted = instance();
        assertNull(restarted.begin("principal@svec.edu", "10.0.11.6"));         // still closed to new addresses
        assertNotNull(restarted.begin("principal@svec.edu", "10.0.9.9"), "the Principal's own machine still works after a restart");
    }

    @Test
    void aLookAlikeSpellingDoesNotBorrowTheOwnersExemption() {
        throttle.succeeded(throttle.begin("principal@svec.edu", "10.0.9.9"));
        for (int a = 1; a <= 5; a++) fail("príncipal@svec.edu", "10.0.12." + a, 5);
        // The database would match the accented spelling to the Principal's row; the exemption is for the exact account only.
        assertNull(throttle.begin("príncipal@svec.edu", "10.0.9.9"));
    }
}
