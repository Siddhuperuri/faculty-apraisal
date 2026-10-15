package edu.svec.fams.appraisal;

import java.util.EnumSet;
import java.util.Set;

/**
 * Appraisal workflow states. Transitions are enforced here (and in the service layer), never only in the UI.
 * The chain is faculty -> Head of the Department -> Principal: the faculty member submits, the HoD reviews and forwards
 * it with a recommendation, and the Principal's approval is final. The Director Technical stands at the Principal's level:
 * either may take up an appraisal the HoD has forwarded, and either one's approval is final. Nothing moves backwards
 * but one step: while the HoD is reviewing and has sent the faculty member a message, the faculty member may correct
 * the appraisal and send it again, which puts it back at SUBMITTED for the HoD to begin the review afresh.
 * (Statuses of the earlier, longer chain survive only as text in the review history; see migration V8.)
 */
public enum AppraisalStatus {
    DRAFT,
    SUBMITTED,
    HOD_REVIEW,
    HOD_APPROVED,
    PRINCIPAL_REVIEW,
    APPROVED;

    public Set<AppraisalStatus> allowedNext() {
        return switch (this) {
            case DRAFT -> EnumSet.of(SUBMITTED);
            case SUBMITTED -> EnumSet.of(HOD_REVIEW);
            case HOD_REVIEW -> EnumSet.of(HOD_APPROVED, SUBMITTED);
            case HOD_APPROVED -> EnumSet.of(PRINCIPAL_REVIEW);
            case PRINCIPAL_REVIEW -> EnumSet.of(APPROVED);
            case APPROVED -> EnumSet.noneOf(AppraisalStatus.class);
        };
    }

    /**
     * Where an appraisal goes when it is revoked, which needs no one's consent and is possible at any stage before the
     * final approval. A revoke always moves it one level back, with the reviewer's remarks:
     * <ul>
     *   <li>the Head of the Department's revoke goes to the faculty member, as a draft to correct and send again, whether it
     *       was still with the HoD or already forwarded;</li>
     *   <li>the Principal's or the Director Technical's revoke of an appraisal the HoD forwarded goes back to the HoD, who is
     *       then reviewing it again.</li>
     * </ul>
     * Null when that role cannot revoke it from here (a draft was never sent; an approved one is final; the Principal and
     * the Director Technical never see anything the HoD has not forwarded).
     */
    public AppraisalStatus revokeTarget(edu.svec.fams.auth.Role by) {
        return switch (by) {
            case HOD -> switch (this) {
                case SUBMITTED, HOD_REVIEW, HOD_APPROVED, PRINCIPAL_REVIEW -> DRAFT;
                case DRAFT, APPROVED -> null;
            };
            case PRINCIPAL, DIRECTOR -> switch (this) {
                case HOD_APPROVED, PRINCIPAL_REVIEW -> HOD_REVIEW;
                case DRAFT, SUBMITTED, HOD_REVIEW, APPROVED -> null;
            };
            case FACULTY, ADMIN -> null;
        };
    }

    public boolean canTransitionTo(AppraisalStatus next) {
        return allowedNext().contains(next);
    }

    /** Faculty may edit a draft; see {@link AppraisalAccess.Core#editableByFaculty()} for the one other time. */
    public boolean isEditableByFaculty() {
        return this == DRAFT;
    }
}
