package edu.svec.fams.common;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

/** Running an INSERT into a table with an auto-increment key and getting that key back. */
public final class GeneratedKeys {
    private GeneratedKeys() {}

    /**
     * Runs the statement and returns the key the database generated.
     *
     * @throws IllegalStateException if the database returned no key (the statement was not an insert into such a table)
     */
    public static long insert(JdbcClient.StatementSpec statement) {
        KeyHolder keys = new GeneratedKeyHolder();
        statement.update(keys);
        Number key = keys.getKey();
        if (key == null) throw new IllegalStateException("The insert returned no generated key");
        return key.longValue();
    }
}
