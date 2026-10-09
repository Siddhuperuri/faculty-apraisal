package edu.svec.fams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.svec.fams.auth.LoginRateLimiter;
import edu.svec.fams.auth.LoginRateLimiter.Outcome;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/** The attempt counter against the real table: the limits must hold in the database, where every instance shares them. */
@SpringBootTest
@ActiveProfiles("test")
class LoginRateLimiterTest {

    /** A clock the test can move forward. */
    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-03T10:00:00Z");
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration d) { now = now.plus(d); }
    }

    @Autowired JdbcClient jdbc;
    @Autowired TestDb db;

    private final MutableClock clock = new MutableClock();
    private LoginRateLimiter limiter;

    @BeforeEach
    void setUp() {
        db.reset();
        limiter = instance();
    }

    /** Another instance of the backend (or the same one after a restart): its own memory, the same database. */
    private LoginRateLimiter instance() { return new LoginRateLimiter(jdbc, clock); }

    private void take(String key, int times, int limit) {
        for (int i = 0; i < times; i++) assertTrue(limiter.tryAcquire(key, limit).granted(), "attempt " + (i + 1));
    }

    @Test
    void grantsUpToTheLimitThenRefuses() {
        take("a", 4, 5);
        assertFalse(limiter.isBlocked("a", 5));
        take("a", 1, 5);
        assertTrue(limiter.isBlocked("a", 5));
        assertFalse(limiter.tryAcquire("a", 5).granted());
    }

    @Test
    void theFirstRefusalIsToldApartFromLaterOnes() {
        take("a", 5, 5);
        assertEquals(Outcome.REFUSED, limiter.tryAcquire("a", 5));        // the one worth a log line
        assertEquals(Outcome.REFUSED_AGAIN, limiter.tryAcquire("a", 5));
        assertEquals(Outcome.REFUSED_AGAIN, limiter.tryAcquire("a", 5));
    }

    @Test
    void keysAreIndependent() {
        take("a", 5, 5);
        assertTrue(limiter.isBlocked("a", 5));
        assertFalse(limiter.isBlocked("b", 5));
        assertTrue(limiter.tryAcquire("b", 5).granted());
    }

    @Test
    void blockExpiresAfterTheWindow() {
        take("a", 5, 5);
        clock.advance(Duration.ofMinutes(11));
        assertFalse(limiter.isBlocked("a", 5));
        assertTrue(limiter.tryAcquire("a", 5).granted());
    }

    @Test
    void attemptsAfterTheWindowStartAFreshCount() {
        take("a", 4, 5);
        clock.advance(Duration.ofMinutes(11));
        take("a", 1, 5);                       // new window, count = 1
        assertFalse(limiter.isBlocked("a", 5));
        take("a", 4, 5);
        assertEquals(Outcome.REFUSED, limiter.tryAcquire("a", 5));     // and the new window refuses, and says so, afresh
    }

    @Test
    void resetClearsTheCounter() {
        take("a", 4, 5);
        limiter.reset("a");
        take("a", 1, 5);
        assertFalse(limiter.isBlocked("a", 5));
    }

    @Test
    void releaseGivesBackExactlyOneAttempt() {
        take("a", 5, 5);
        limiter.release("a");
        assertFalse(limiter.isBlocked("a", 5));
        take("a", 1, 5);
        assertTrue(limiter.isBlocked("a", 5));
        limiter.release("never-seen");          // harmless
    }

    // ---- shared across instances and restarts ----

    @Test
    void everyInstanceCountsAgainstTheSameLimit() {
        LoginRateLimiter second = instance();
        take("a", 3, 5);
        assertTrue(second.tryAcquire("a", 5).granted());
        assertTrue(second.tryAcquire("a", 5).granted());
        // five between them: neither instance grants a sixth, and only the first refusal is the one to log
        assertEquals(Outcome.REFUSED, limiter.tryAcquire("a", 5));
        assertEquals(Outcome.REFUSED_AGAIN, second.tryAcquire("a", 5));
        // a success seen by one instance clears the count for the other
        second.reset("a");
        assertFalse(limiter.isBlocked("a", 5));
    }

    @Test
    void aRestartForgetsNothing() {
        take("a", 5, 5);
        limiter = instance();                   // the server comes back with empty memory
        assertTrue(limiter.isBlocked("a", 5));
        assertFalse(limiter.tryAcquire("a", 5).granted());
        clock.advance(Duration.ofMinutes(11));
        assertTrue(limiter.tryAcquire("a", 5).granted());
    }

    @Test
    void theTableHoldsNoAddressOrEmailOnlyAHashOfTheKey() {
        take("pair|principal@svec.edu|10.0.0.5", 1, 5);
        String stored = jdbc.sql("SELECT key_hash FROM sign_in_attempts").query(String.class).single();
        assertTrue(stored.matches("[0-9a-f]{64}"), stored);
    }

    /**
     * The reason attempts are counted before the password is checked: however many requests arrive at the same
     * instant, on however many instances, no more than the limit get through.
     */
    @Test
    void aBurstOfParallelAttemptsCannotExceedTheLimit() throws Exception {
        int threads = 32;
        LoginRateLimiter second = instance();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger granted = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            LoginRateLimiter mine = i % 2 == 0 ? limiter : second;
            pool.submit(() -> {
                start.await();
                if (mine.tryAcquire("burst", 5).granted()) granted.incrementAndGet();
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));
        assertEquals(5, granted.get());
        assertEquals(5, jdbc.sql("SELECT attempts FROM sign_in_attempts").query(Integer.class).single());
    }

    @Test
    void aFloodOfMadeUpKeysCannotGrowTheTableWithoutBound() {
        // A small ceiling stands in for the real one. Once it is reached, new keys are refused rather than stored.
        limiter = new LoginRateLimiter(jdbc, clock, 20);
        for (int i = 0; i < 20; i++) assertTrue(limiter.tryAcquire("flood-" + i, 5).granted());
        clock.advance(Duration.ofSeconds(31));              // the ceiling is looked at now and then, not on every request
        assertFalse(limiter.tryAcquire("flood-new", 5).granted());
        assertEquals(20, jdbc.sql("SELECT COUNT(*) FROM sign_in_attempts").query(Integer.class).single());
        // Keys that are already tracked keep working, and expired ones make room again.
        assertTrue(limiter.tryAcquire("flood-0", 5).granted());
        clock.advance(Duration.ofMinutes(11));
        assertTrue(limiter.tryAcquire("after-the-flood", 5).granted());
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM sign_in_attempts").query(Integer.class).single());
    }
}
