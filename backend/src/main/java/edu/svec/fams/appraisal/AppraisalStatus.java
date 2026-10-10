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

    public boolean canTransitionTo(AppraisalStatus next) {
        return allowedNext().contains(next);
    }

    /** Faculty may edit a draft; see {@link AppraisalAccess.Core#editableByFaculty()} for the one other time. */
    public boolean isEditableByFaculty() {
        return this == DRAFT;
    }
}
