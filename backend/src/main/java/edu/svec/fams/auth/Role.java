package edu.svec.fams.auth;

import java.util.Optional;

/**
 * The levels of the hierarchy, in the order an appraisal travels: faculty, then HoD, then the Principal or the Director
 * Technical. The two stand at the same level: either may begin the review of an appraisal the HoD has forwarded and give the
 * final approval.
 */
public enum Role {
    FACULTY, HOD, PRINCIPAL, DIRECTOR, ADMIN;

    /**
     * The role stored on a user row, if it is one that still exists. Accounts of the withdrawn Dean and Vice Principal
     * roles keep their stored role for the record (they are closed, see migration V8) and match nothing here.
     */
    public static Optional<Role> find(String stored) {
        for (Role r : values()) {
            if (r.name().equals(stored)) return Optional.of(r);
        }
        return Optional.empty();
    }
}
