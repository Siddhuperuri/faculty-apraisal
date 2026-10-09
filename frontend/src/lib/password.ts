/**
 * The password rules as a hint while typing. The server applies the same rules (and a few more, such as a list of very
 * common passwords) and is the authority: its message is shown if it refuses something this lets through.
 */
export const MIN_PASSWORD_LENGTH = 10;
export const MAX_PASSWORD_BYTES = 72;

export interface PasswordCheck {
  rule: string;
  ok: boolean;
}

export function byteLength(text: string): number {
  return new TextEncoder().encode(text).length;
}

/** Each rule with whether the candidate meets it, for a live checklist. */
export function passwordChecks(candidate: string, email?: string): PasswordCheck[] {
  const local = email && email.includes("@") ? email.slice(0, email.indexOf("@")).toLowerCase() : "";
  return [
    { rule: `At least ${MIN_PASSWORD_LENGTH} characters`, ok: candidate.length >= MIN_PASSWORD_LENGTH },
    { rule: "A letter and a number", ok: /\p{L}/u.test(candidate) && /\p{N}/u.test(candidate) },
    { rule: "No space at the start or end", ok: candidate === candidate.trim() },
    { rule: `At most ${MAX_PASSWORD_BYTES} bytes`, ok: byteLength(candidate) <= MAX_PASSWORD_BYTES },
    ...(local.length >= 4 ? [{ rule: "Not containing your e-mail name", ok: !candidate.toLowerCase().includes(local) }] : []),
  ];
}

/** The first rule not met, or null when the candidate is acceptable. */
export function passwordProblem(candidate: string, email?: string): string | null {
  const failed = passwordChecks(candidate, email).find((c) => !c.ok);
  return failed ? failed.rule : null;
}
