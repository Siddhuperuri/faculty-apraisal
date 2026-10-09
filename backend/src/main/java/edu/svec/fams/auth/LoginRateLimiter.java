package edu.svec.fams.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Attempt counter: at most {@code limit} attempts per key inside a fixed window.
 *
 * <p>An attempt is counted <em>before</em> the password is checked ({@link #tryAcquire}) and given back if it turns out
 * to be correct ({@link #reset} / {@link #release}). Checking first and counting afterwards would let a burst of
 * parallel requests all pass the check before any of them is counted.
 *
 * <p>The counters live in the database (table {@code sign_in_attempts}), so they are shared by every instance of the
 * backend and survive a restart. Each change is one conditional statement that the database applies atomically, which
 * is what holds the limit under parallel requests, whichever instance they reach. The statements run outside any
 * surrounding transaction: a counted attempt must stay counted when the caller's own work is rolled back.
 *
 * <p>Bounded: dead rows are purged from time to time, and past {@link #MAX_ENTRIES} keys nothing new is admitted
 * (it fails closed).
 */
@Component
public class LoginRateLimiter {
    static final Duration WINDOW = Duration.ofMinutes(10);
    static final Duration PURGE_INTERVAL = Duration.ofSeconds(30);
    /** A flood of made-up keys cannot grow the table past roughly this; real use is a few hundred keys. */
    static final int MAX_ENTRIES = 100_000;
    private static final int PURGE_BATCH = 10_000;

    public enum Outcome {
        GRANTED,
        /** Over the limit, and this is the first refusal in the window: the moment worth logging. */
        REFUSED,
        /** Over the limit, already refused before in this window. */
        REFUSED_AGAIN;

        public boolean granted() { return this == GRANTED; }
    }

    private final JdbcClient jdbc;
    private final Clock clock;
    private final int maxEntries;
    private final AtomicLong lastPurge = new AtomicLong(Long.MIN_VALUE);
    private volatile boolean full;

    @Autowired
    public LoginRateLimiter(JdbcClient jdbc, Clock clock) { this(jdbc, clock, MAX_ENTRIES); }

    /** @param maxEntries the ceiling on tracked keys ({@link #MAX_ENTRIES} in the application) */
    public LoginRateLimiter(JdbcClient jdbc, Clock clock, int maxEntries) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.maxEntries = maxEntries;
    }

    /** Counts one attempt against {@code key} if fewer than {@code limit} are already counted in the current window. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Outcome tryAcquire(String key, int limit) {
        long now = clock.millis();
        long live = now - WINDOW.toMillis();        // a window that began at or after this is still running
        String hash = hash(key);
        purgeNowAndThen(now, live);

        // Each step is atomic on its own; another request may change the row between two of them, so a pass that
        // matches nothing is simply taken again.
        for (int pass = 0; pass < 3; pass++) {
            if (jdbc.sql("UPDATE sign_in_attempts SET attempts = attempts + 1 WHERE key_hash = ? AND window_start_ms >= ? AND attempts < ?")
                    .params(hash, live, limit).update() == 1) {
                return Outcome.GRANTED;
            }
            if (jdbc.sql("UPDATE sign_in_attempts SET attempts = 1, window_start_ms = ?, refused = FALSE WHERE key_hash = ? AND window_start_ms < ?")
                    .params(now, hash, live).update() == 1) {
                return Outcome.GRANTED;
            }
            if (!full) {
                try {
                    jdbc.sql("INSERT INTO sign_in_attempts (key_hash, attempts, window_start_ms) VALUES (?, 1, ?)").params(hash, now).update();
                    return Outcome.GRANTED;
                } catch (DuplicateKeyException alreadyThere) {
                    // the row exists: it is at its limit, or another request has just created it
                }
            }
            if (jdbc.sql("UPDATE sign_in_attempts SET refused = TRUE WHERE key_hash = ? AND window_start_ms >= ? AND attempts >= ? AND refused = FALSE")
                    .params(hash, live, limit).update() == 1) {
                return Outcome.REFUSED;
            }
            Optional<Integer> counted = counted(hash, live);
            if (counted.isEmpty() ? full : counted.get() >= limit) return Outcome.REFUSED_AGAIN;
        }
        return Outcome.REFUSED_AGAIN;
    }

    /** Gives back one counted attempt (the attempt was not a failure after all). */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void release(String key) {
        jdbc.sql("UPDATE sign_in_attempts SET attempts = attempts - 1 WHERE key_hash = ? AND attempts > 0").param(hash(key)).update();
    }

    /** Forgets the key (a correct password clears the earlier failures). */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void reset(String key) {
        jdbc.sql("DELETE FROM sign_in_attempts WHERE key_hash = ?").param(hash(key)).update();
    }

    /** Whether another attempt would be refused. Changes nothing. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public boolean isBlocked(String key, int limit) {
        return counted(hash(key), clock.millis() - WINDOW.toMillis()).orElse(0) >= limit;
    }

    int size() {
        return jdbc.sql("SELECT COUNT(*) FROM sign_in_attempts").query(Integer.class).single();
    }

    /** Attempts counted for the key in its running window; empty if it has none. */
    private Optional<Integer> counted(String hash, long live) {
        return jdbc.sql("SELECT attempts FROM sign_in_attempts WHERE key_hash = ? AND window_start_ms >= ?")
                .params(hash, live).query(Integer.class).optional();
    }

    /**
     * Removes dead rows and notes whether the table has reached its ceiling. Counting the table on every request would
     * itself be a way to slow the server down, so each instance does this now and then.
     */
    private void purgeNowAndThen(long now, long live) {
        long last = lastPurge.get();
        if (last != Long.MIN_VALUE && now - last < PURGE_INTERVAL.toMillis()) return;
        if (!lastPurge.compareAndSet(last, now)) return;
        jdbc.sql("DELETE FROM sign_in_attempts WHERE window_start_ms < ? LIMIT " + PURGE_BATCH).param(live).update();
        full = size() >= maxEntries;
    }

    /** The key is stored as its SHA-256, so the table holds no e-mail address or client address. */
    private static String hash(String key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // SHA-256 is always present
        }
    }
}
