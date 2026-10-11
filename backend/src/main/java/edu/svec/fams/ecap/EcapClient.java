package edu.svec.fams.ecap;

import java.util.List;
import java.util.Optional;

/**
 * The college's ECAP system as the source of course information: a course's code and name are chosen from what ECAP
 * holds, never typed. Implementations throw {@link EcapUnavailableException} when ECAP cannot be reached, so callers can
 * say so plainly instead of failing.
 */
public interface EcapClient {

    /** Courses whose code or name matches the text, at most {@code limit}. */
    List<EcapCourse> search(String query, int limit);

    /** The course with exactly this code, if ECAP has it. */
    Optional<EcapCourse> find(String code);
}
