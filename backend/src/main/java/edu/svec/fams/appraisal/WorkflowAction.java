package edu.svec.fams.appraisal;

import static edu.svec.fams.appraisal.AppraisalStatus.*;

import edu.svec.fams.auth.Role;
import edu.svec.fams.common.ApiException;

/**
 * A named workflow step: which role may perform it and where it leads. The names are stored in review_actions.action
 * (24 characters at most). The Principal and the Director Technical stand at the same level, so each has its own pair of
 * steps to the same statuses: the record then says which of them acted.
 */
public enum WorkflowAction {
    SUBMIT(Role.FACULTY, SUBMITTED),
    START_HOD_REVIEW(Role.HOD, HOD_REVIEW),
    HOD_APPROVE(Role.HOD, HOD_APPROVED),
    START_PRINCIPAL_REVIEW(Role.PRINCIPAL, PRINCIPAL_REVIEW),
    PRINCIPAL_APPROVE(Role.PRINCIPAL, APPROVED),
    START_DIRECTOR_REVIEW(Role.DIRECTOR, PRINCIPAL_REVIEW),
    DIRECTOR_APPROVE(Role.DIRECTOR, APPROVED);

    public enum Verb { SUBMIT, START, APPROVE }

    private final Role role;
    private final AppraisalStatus target;

    WorkflowAction(Role role, AppraisalStatus target) {
        this.role = role;
        this.target = target;
    }

    public Role role() { return role; }
    public AppraisalStatus target() { return target; }

    public static WorkflowAction resolve(Role role, Verb verb) {
        return switch (role) {
            case FACULTY -> verb == Verb.SUBMIT ? SUBMIT : denied();
            case HOD -> reviewer(verb, START_HOD_REVIEW, HOD_APPROVE);
            case PRINCIPAL -> reviewer(verb, START_PRINCIPAL_REVIEW, PRINCIPAL_APPROVE);
            case DIRECTOR -> reviewer(verb, START_DIRECTOR_REVIEW, DIRECTOR_APPROVE);
            case ADMIN -> denied();
        };
    }

    private static WorkflowAction reviewer(Verb verb, WorkflowAction start, WorkflowAction approve) {
        return switch (verb) {
            case START -> start;
            case APPROVE -> approve;
            default -> denied();
        };
    }

    private static WorkflowAction denied() {
        throw ApiException.forbidden("Your role cannot perform this action.");
    }
}
