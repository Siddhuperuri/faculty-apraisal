package edu.svec.fams.auth;

/** Whether an account may sign in. Stored in users.status (and limited to these values by a CHECK constraint). */
public enum AccountStatus {
    ACTIVE, DISABLED;

    /** True for the stored value of an account that may sign in. */
    public static boolean isActive(String stored) { return ACTIVE.name().equals(stored); }
}
