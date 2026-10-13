import type { DeletionPreview, DeletionResult } from "./types";

/**
 * Deleting an account is the one irreversible thing an administrator can do to a person, so what is said and what is
 * needed before it happens is kept here, apart from the screen, where it can be tested.
 */

/** The confirmation is the account's own e-mail address, typed; capitals and surrounding spaces do not matter. */
export function confirmationMatches(email: string, typed: string): boolean {
  const wanted = email.trim().toLowerCase();
  return wanted !== "" && typed.trim().toLowerCase() === wanted;
}

export interface DeletionWarning {
  headline: string;
  /** What goes, in plain words. */
  removes: string[];
  /** What cannot go (the records the review depends on), and how it is kept. */
  keeps: string[];
  /** Consequences for others the administrator should know before confirming. */
  consequences: string[];
}

const plural = (n: number, one: string, many: string) => `${n} ${n === 1 ? one : many}`;

/** The warning shown before an account is deleted: who, what is removed, what is kept anonymously, and what follows. */
export function deletionWarning(p: DeletionPreview, roleName: string): DeletionWarning {
  const who = p.name && p.name !== p.email ? `${p.name} (${p.email})` : p.email;
  const removes = ["Their sign-in. They can never sign in again, and any session they have open ends at once.", "Any password reset links sent to them."];
  if (p.role === "FACULTY") removes.push("Their faculty record: name, employee ID, contact details and other profile details.");
  if (p.appraisalsRemoved > 0) {
    removes.push(`${plural(p.appraisalsRemoved, "appraisal", "appraisals")} never sent for review, with everything entered in ${p.appraisalsRemoved === 1 ? "it" : "them"}.`);
  }
  if (p.departmentsUnassigned.length > 0) removes.push(`Their assignment as Head of the Department of ${p.departmentsUnassigned.join(", ")}.`);

  const keeps: string[] = [];
  if (p.appraisalsKept > 0) {
    keeps.push(
      `${plural(p.appraisalsKept, "appraisal", "appraisals")} that went through review, kept without the person: the name, employee ID and contact details are taken out. ` +
        "Official reports already issued are not changed.",
    );
  }
  if (p.keepsAnonymousRecord) {
    keeps.push('The review history, messages and operation records that name this account, which will show only "Deleted user".');
  }
  keeps.push("The audit trail entry that this account was deleted, by whom and when. It holds neither the e-mail address nor the name.");

  const consequences = ["This cannot be undone."];
  if (p.departmentsLeftWithoutHead.length > 0) {
    consequences.unshift(`${p.departmentsLeftWithoutHead.join(", ")} will have no Head of the Department until you assign one, so submitted appraisals there cannot be reviewed.`);
  }
  return { headline: `Delete ${who}, ${roleName}, permanently?`, removes, keeps, consequences };
}

/** The line shown once it is done. */
export function deletionResultMessage(email: string, r: DeletionResult): string {
  const parts = [`${email} was deleted permanently.`];
  if (r.appraisalsRemoved > 0) parts.push(`${plural(r.appraisalsRemoved, "appraisal was", "appraisals were")} removed with it.`);
  if (r.appraisalsKept > 0) parts.push(`${plural(r.appraisalsKept, "appraisal was", "appraisals were")} kept without the person.`);
  return parts.join(" ");
}
