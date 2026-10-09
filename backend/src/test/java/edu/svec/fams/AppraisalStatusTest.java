package edu.svec.fams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.svec.fams.appraisal.AppraisalAccess;
import edu.svec.fams.appraisal.AppraisalStatus;
import edu.svec.fams.appraisal.WorkflowAction;
import edu.svec.fams.auth.Role;
import edu.svec.fams.common.ApiException;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AppraisalStatusTest {

    /** The whole chain: faculty, then the Head of the Department, then the Principal. */
    private static final List<AppraisalStatus> CHAIN = List.of(
            AppraisalStatus.DRAFT, AppraisalStatus.SUBMITTED,
            AppraisalStatus.HOD_REVIEW, AppraisalStatus.HOD_APPROVED,
            AppraisalStatus.PRINCIPAL_REVIEW, AppraisalStatus.APPROVED);

    @Test
    void theChainIsFacultyThenHodThenPrincipalOrDirectorAndNothingElse() {
        assertEquals(CHAIN, List.of(AppraisalStatus.values()), "no status outside the chain (no Dean, no Vice Principal, no returned)");
        assertEquals(List.of(Role.FACULTY, Role.HOD, Role.PRINCIPAL, Role.DIRECTOR, Role.ADMIN), List.of(Role.values()));
    }

    @Test
    void everyStatusHasExactlyOneWayForwardAndApprovedIsTerminal() {
        for (int i = 0; i + 1 < CHAIN.size(); i++) {
            // The one exception: under the HoD's review the faculty member may send the appraisal again (see the next test).
            Set<AppraisalStatus> expected = CHAIN.get(i) == AppraisalStatus.HOD_REVIEW
                    ? EnumSet.of(AppraisalStatus.HOD_APPROVED, AppraisalStatus.SUBMITTED) : EnumSet.of(CHAIN.get(i + 1));
            assertEquals(expected, CHAIN.get(i).allowedNext(), CHAIN.get(i) + " leads only to " + expected);
        }
        assertTrue(AppraisalStatus.APPROVED.allowedNext().isEmpty());
    }

    @Test
    void onlyOneStepEverMovesBackwardsAndThatIsTheFacultyMemberSendingItAgainUnderReview() {
        // There is no return or revert by a reviewer: from any status, no earlier status (and not the status itself) can
        // follow, except HOD_REVIEW back to SUBMITTED, which only the faculty member's RESUBMIT takes.
        assertTrue(AppraisalStatus.HOD_REVIEW.canTransitionTo(AppraisalStatus.SUBMITTED));
        assertEquals(Role.FACULTY, WorkflowAction.RESUBMIT.role());
        for (int i = 0; i < CHAIN.size(); i++) {
            for (int j = 0; j <= i; j++) {
                if (CHAIN.get(i) == AppraisalStatus.HOD_REVIEW && CHAIN.get(j) == AppraisalStatus.SUBMITTED) continue;
                assertFalse(CHAIN.get(i).canTransitionTo(CHAIN.get(j)), CHAIN.get(i) + " must not lead back to " + CHAIN.get(j));
            }
        }
    }

    @Test
    void noLevelCanBeSkipped() {
        for (int i = 0; i < CHAIN.size(); i++) {
            for (int j = i + 2; j < CHAIN.size(); j++) {
                assertFalse(CHAIN.get(i).canTransitionTo(CHAIN.get(j)), CHAIN.get(i) + " must not jump to " + CHAIN.get(j));
            }
        }
    }

    @Test
    void onlyADraftIsEditableByFaculty() {
        for (AppraisalStatus s : AppraisalStatus.values()) {
            assertEquals(s == AppraisalStatus.DRAFT, s.isEditableByFaculty(), s.name());
        }
    }

    @Test
    void theOnlyStepsAreSubmitResubmitStartAndApprove() {
        assertEquals(List.of(WorkflowAction.SUBMIT, WorkflowAction.RESUBMIT, WorkflowAction.START_HOD_REVIEW, WorkflowAction.HOD_APPROVE,
                WorkflowAction.START_PRINCIPAL_REVIEW, WorkflowAction.PRINCIPAL_APPROVE,
                WorkflowAction.START_DIRECTOR_REVIEW, WorkflowAction.DIRECTOR_APPROVE), List.of(WorkflowAction.values()));
        assertEquals(List.of(WorkflowAction.Verb.SUBMIT, WorkflowAction.Verb.START, WorkflowAction.Verb.APPROVE),
                List.of(WorkflowAction.Verb.values()));
        for (WorkflowAction a : WorkflowAction.values()) {
            boolean reachable = false;
            for (AppraisalStatus s : AppraisalStatus.values()) reachable |= s.canTransitionTo(a.target());
            assertTrue(reachable, a + " targets a state nothing can reach");
        }
    }

    @Test
    void rolesMapToTheirOwnStepsOnly() {
        assertEquals(WorkflowAction.SUBMIT, WorkflowAction.resolve(Role.FACULTY, WorkflowAction.Verb.SUBMIT));
        Object[][] reviewers = {
                {Role.HOD, WorkflowAction.START_HOD_REVIEW, WorkflowAction.HOD_APPROVE},
                {Role.PRINCIPAL, WorkflowAction.START_PRINCIPAL_REVIEW, WorkflowAction.PRINCIPAL_APPROVE},
                {Role.DIRECTOR, WorkflowAction.START_DIRECTOR_REVIEW, WorkflowAction.DIRECTOR_APPROVE}};
        for (Object[] e : reviewers) {
            Role role = (Role) e[0];
            assertEquals(e[1], WorkflowAction.resolve(role, WorkflowAction.Verb.START));
            assertEquals(e[2], WorkflowAction.resolve(role, WorkflowAction.Verb.APPROVE));
            assertEquals(role, ((WorkflowAction) e[1]).role());
            assertEquals(role, ((WorkflowAction) e[2]).role());
            assertForbidden(role, WorkflowAction.Verb.SUBMIT);
        }
        // The HoD's approval forwards it to the Principal or the Director Technical, who stand at the same level: either one's is final.
        assertEquals(AppraisalStatus.HOD_APPROVED, WorkflowAction.HOD_APPROVE.target());
        assertEquals(AppraisalStatus.APPROVED, WorkflowAction.PRINCIPAL_APPROVE.target());
        assertEquals(AppraisalStatus.APPROVED, WorkflowAction.DIRECTOR_APPROVE.target());
        assertEquals(WorkflowAction.START_PRINCIPAL_REVIEW.target(), WorkflowAction.START_DIRECTOR_REVIEW.target());
        for (WorkflowAction.Verb v : WorkflowAction.Verb.values()) {
            if (v != WorkflowAction.Verb.SUBMIT) assertForbidden(Role.FACULTY, v);
            assertForbidden(Role.ADMIN, v);
        }
    }

    @Test
    void namesFitTheDatabaseColumns() {
        for (WorkflowAction a : WorkflowAction.values()) {
            assertTrue(a.name().length() <= 24, a + " is too long for review_actions.action");
        }
        for (AppraisalStatus s : AppraisalStatus.values()) {
            assertTrue(s.name().length() <= 24, s + " is too long for the status columns");
        }
        for (Role r : Role.values()) assertTrue(r.name().length() <= 16, r + " is too long for the role columns");
    }

    @Test
    void thePrincipalAndTheDirectorSeeAnAppraisalFromTheHodsApprovalOnwardAndNothingEarlier() {
        Set<AppraisalStatus> principal = AppraisalAccess.finalLevelVisible();
        assertEquals(EnumSet.of(AppraisalStatus.HOD_APPROVED, AppraisalStatus.PRINCIPAL_REVIEW, AppraisalStatus.APPROVED), principal);
        assertThrows(UnsupportedOperationException.class, () -> principal.add(AppraisalStatus.DRAFT));
    }

    @Test
    void withdrawnRolesAreNotRoles() {
        assertTrue(Role.find("HOD").isPresent());
        assertTrue(Role.find("DEAN").isEmpty());
        assertTrue(Role.find("VICE_PRINCIPAL").isEmpty());
        assertTrue(Role.find(null).isEmpty());
    }

    private static void assertForbidden(Role role, WorkflowAction.Verb verb) {
        ApiException e = assertThrows(ApiException.class, () -> WorkflowAction.resolve(role, verb));
        assertEquals(org.springframework.http.HttpStatus.FORBIDDEN, e.status());
    }
}
