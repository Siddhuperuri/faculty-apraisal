export type Role = "FACULTY" | "HOD" | "PRINCIPAL" | "DIRECTOR" | "ADMIN";

/**
 * Roles that no longer exist. Their accounts are closed, but the name still appears in older review history and in the
 * administrator's list of accounts.
 */
export type WithdrawnRole = "DEAN" | "VICE_PRINCIPAL";

export type Status = "DRAFT" | "SUBMITTED" | "HOD_REVIEW" | "HOD_APPROVED" | "PRINCIPAL_REVIEW" | "APPROVED";

export interface Me {
  id: number;
  email: string;
  role: Role;
  /** An administrator issued a one-time password: nothing else is allowed until it is replaced. */
  mustChangePassword: boolean;
}

export interface ListItem {
  id: number;
  facultyName: string;
  employeeId: string;
  department: string;
  academicYear: string;
  status: Status;
  submittedAt: string | null;
  updatedAt: string;
  /** The HoD is reviewing it and has sent the faculty member a message: it is waiting for them. */
  queryRaised: boolean;
}

/** One part of a criterion's maximum for the cadre, in the college's wording. */
export interface ScoreComponent {
  description: string;
  maxMarks: number;
  /** Marks the application works out for this part itself (Teaching & Learning); null where the parts are not marked one by one. */
  awarded: number | null;
}

export interface ScoreRow {
  criterion: string;
  /** The criterion's number in Part B, as the college's documents cite it ("B1"). */
  reference: string;
  /** The criterion's name exactly as printed on the form's score sheet. */
  label: string;
  /** Null for the criteria marked per entry (B5 to B9): they have no maximum. */
  maxMarks: number | null;
  /** The marks that count, worked out from the entries so far. Nobody types a score. */
  score: number;
  /** The parts the maximum is made of for this cadre; empty where the college gives no breakdown. */
  components: ScoreComponent[];
  /** The marks per entry with how many the faculty member has: marks = perEntry x count. */
  breakdown: ScoreLine[];
}

export interface ScoreLine {
  description: string;
  perEntry: number;
  count: number;
  marks: number;
  /** The most this kind of entry can earn; null where there is no such limit. */
  cap: number | null;
}

/** A step of the review as it was recorded. Older appraisals may name steps, statuses and roles that no longer exist. */
export interface HistoryRow {
  action: string;
  fromStatus: string;
  toStatus: string;
  actorRole: Role | WithdrawnRole;
  comment: string | null;
  at: string;
}

export interface AppraisalView {
  id: number;
  status: Status;
  academicYear: string;
  facultyName: string;
  employeeId: string;
  email: string;
  department: string;
  cadre: string;
  editable: boolean;
  submittedAt: string | null;
  finalApprovedAt: string | null;
  declaredAt: string | null;
  scores: ScoreRow[];
  history: HistoryRow[];
  /** What is still missing before it can be submitted to the HoD; empty when it is ready (or not the owner's to submit). */
  submitBlockers: string[];
  /** The HoD is reviewing it and has sent the faculty member a message. */
  queryRaised: boolean;
  /** The first and last day of the academic year this appraisal is for (YYYY-MM-DD). */
  academicYearStart: string;
  academicYearEnd: string;
}

export type FieldType = "TEXT" | "INT" | "DECIMAL" | "ENUM" | "DATE" | "MONTH_YEAR";

export interface FieldMeta {
  name: string;
  label: string;
  type: FieldType;
  required: boolean;
  /** May stay empty in a draft but must be filled before the appraisal can be submitted. */
  submitRequired?: boolean;
  maxLength: number | null;
  min: number | null;
  max: number | null;
  scale: number | null;
  allowed: string[] | null;
  /** The field whose value decides which choices are offered here (a branch depends on the program). */
  dependsOn?: string | null;
  /** For each value of `dependsOn`, the choices offered. */
  allowedBy?: Record<string, string[]> | null;
  /** Worked out by the server from other fields (days from the dates); shown, not typed. */
  derived?: boolean;
  /** Must fall in the academic year being appraised (1 June to 31 May): only what happened in that year is considered. */
  inAcademicYear?: boolean;
}

export interface SectionMeta {
  key: string;
  singleton: boolean;
  fields: FieldMeta[];
  dateRanges: { startField: string; endField: string }[];
  uniqueField: string | null;
}

/** One saved record: its id plus the section's fields. */
export type Rec = { id?: number } & Record<string, unknown>;

export interface SectionData {
  section: string;
  singleton: boolean;
  records: Rec[];
  summary: Record<string, number | null> | null;
}

// ---- administration ----

export interface Department {
  id: number;
  code: string;
  name: string;
  active: boolean;
}

export interface Cadre {
  id: number;
  code: string;
  name: string;
  active: boolean;
}

export interface AcademicYear {
  id: number;
  name: string;
  startDate: string;
  endDate: string;
  active: boolean;
}

export interface CriterionInfo {
  code: string;
  label: string;
  /** Marked per entry with no maximum (B5 to B9). */
  perEntry: boolean;
}

export interface AdminReference {
  departments: Department[];
  cadres: Cadre[];
  academicYears: AcademicYear[];
  criteria: CriterionInfo[];
}

export interface AdminUserRow {
  id: number;
  email: string;
  role: Role | WithdrawnRole;
  status: "ACTIVE" | "DISABLED";
  lastLoginAt: string | null;
  mustChangePassword: boolean;
  name: string | null;
  employeeId: string | null;
  department: string | null;
  cadre: string | null;
  hodDepartments: string | null;
}

export interface AdminUserDetail {
  row: AdminUserRow;
  departmentId: number | null;
  cadreId: number | null;
  profile: Record<string, string | number | null>;
  hodDepartmentIds: number[];
}

/** A message from the Head of the Department to the faculty member about an appraisal under review. */
export interface AppraisalMessage {
  id: number;
  senderRole: "HOD";
  /** The Head of the Department's name when the account has one. */
  senderName: string | null;
  body: string;
  sentAt: string;
  /** When the text was last changed by the Head of the Department; null if it never was. */
  editedAt: string | null;
  /** When the faculty member opened it (cleared when the text is edited); null while unseen. */
  readAt: string | null;
  /** Whether the person asking wrote it: the Head of the Department may edit their own while reviewing. */
  mine: boolean;
  /** The wordings an edit replaced, oldest first. Sent to Heads of the Department only: always empty for the faculty member. */
  earlier: MessageVersion[];
}

/** An earlier wording of a message that was edited. */
export interface MessageVersion {
  body: string;
  writtenAt: string;
  replacedAt: string;
}

/** One thing wrong in one row of an uploaded accounts file. `row` is the line of the file; the heading is line 1. */
export interface ImportRowError {
  row: number;
  column: string;
  message: string;
}

/** The outcome of uploading a file of accounts: all of them created, or none and the rows to correct. */
export interface ImportResult {
  accounts: number;
  created: number;
  byRole: Partial<Record<Role, number>>;
  errors: ImportRowError[];
  moreErrors: boolean;
}

/** Returned when an account is created or its password reset: the old password. */
export interface IssuedPassword {
  id: number;
  email: string;
  role: Role;
  temporaryPassword: string;
}

/** Returned when several passwords are set back at once: how many were, how many were left as they were. */
export interface BulkReset {
  reset: number;
  skipped: number;
  temporaryPassword: string;
}

export interface PolicyVersion {
  id: number;
  academicYearId: number;
  cadreId: number;
  cadre: string;
  version: number;
  active: boolean;
  createdAt: string;
  marks: Record<string, number>;
  total: number;
  /** Scoring components by criterion code; a criterion without a breakdown is absent. */
  components: Record<string, ScoreComponent[]>;
}

export interface AuditEntry {
  id: number;
  at: string;
  actorId: number | null;
  actorEmail: string | null;
  action: string;
  entityType: string;
  entityId: number | null;
  details: Record<string, unknown> | null;
}

export interface AuditPage {
  items: AuditEntry[];
  page: number;
  size: number;
  total: number;
}

// ---- consoles ----

/** Where an appraisal is, as the Head of the Department and Principal consoles group them. */
export type Stage = "NOT_SUBMITTED" | "NEEDS_HOD" | "ONWARD" | "APPROVED";

export interface YearRef {
  id: number;
  name: string;
}

export interface DepartmentSummary {
  id: number;
  code: string;
  name: string;
  faculty: number;
  counts: Record<Stage, number>;
}

export interface RosterRow {
  /** Null for someone who has not submitted: a draft is private, so there is nothing to open. */
  appraisalId: number | null;
  name: string;
  employeeId: string;
  department: string;
  cadre: string;
  status: Status | null;
  stage: Stage;
  submittedAt: string | null;
  updatedAt: string | null;
  /** The HoD has sent the faculty member a message about it and the review is still open. */
  queryRaised: boolean;
}

export interface HodConsole {
  year: YearRef | null;
  years: YearRef[];
  departments: DepartmentSummary[];
  totals: Record<Stage, number>;
  roster: RosterRow[];
}

/** One department on the Principal's console. */
export interface PrincipalDepartment {
  id: number;
  code: string;
  name: string;
  faculty: number;
  /** Forwarded by the Head of the Department and waiting for the Principal to decide. */
  awaiting: number;
  approved: number;
  /** Not yet visible to the Principal: not submitted, or still with the Head of the Department. */
  notYetWithYou: number;
}

export interface AwaitingRow {
  appraisalId: number;
  name: string;
  employeeId: string;
  department: string;
  status: Status;
  updatedAt: string;
}

export interface PrincipalConsole {
  year: YearRef | null;
  years: YearRef[];
  departments: PrincipalDepartment[];
  totals: { faculty: number; awaiting: number; approved: number; notYetWithYou: number };
  awaitingList: AwaitingRow[];
}

export interface RoleCount {
  role: Role;
  active: number;
  disabled: number;
}

export interface SetupCheck {
  code: string;
  level: "ok" | "warn" | "problem";
  message: string;
  link: string | null;
}

export interface AdminOverview {
  year: YearRef | null;
  accounts: RoleCount[];
  neverSignedIn: number;
  /** Appraisals by status, plus NOT_STARTED. Counts only, never names. */
  pipeline: Record<string, number>;
  checks: SetupCheck[];
  recent: AuditEntry[];
}

// ---- Administrator operations: backups, storage, account tidiness, imports, year readiness, handover ----

export type HealthLevel = "ok" | "warn" | "problem";

export interface RestoreTest {
  id: number;
  testedOn: string;
  result: "PASSED" | "FAILED";
  notes: string | null;
  recordedBy: string;
  recordedAt: string;
}

export interface BackupHealth {
  level: HealthLevel;
  headline: string;
  configured: boolean;
  destination: string;
  destinationStatus: "OK" | "NOT_SET" | "MISSING" | "NOT_WRITABLE" | "SAME_DISK";
  destinationNote: string;
  destinationFreeBytes: number | null;
  lastBackupAt: string | null;
  ageHours: number | null;
  overdue: boolean;
  maxAgeHours: number;
  lastBackupBytes: number | null;
  lastBackupFiles: number | null;
  lastBackupFolder: string | null;
  lastFailureAt: string | null;
  lastFailureMessage: string | null;
  lastRestoreTest: RestoreTest | null;
  restoreTestOverdue: boolean;
  restoreTestMaxAgeDays: number;
}

export interface StorageHealth {
  level: HealthLevel;
  headline: string;
  path: string;
  diskTotalBytes: number;
  diskFreeBytes: number;
  diskUsedPercent: number;
  warnPercent: number;
  reportBytes: number;
  reportCount: number;
  averageReportBytes: number;
  averageMeasured: boolean;
  databaseBytes: number;
  activeFaculty: number;
  retentionYears: number;
  expectedBytes: number;
  projectedPercent: number;
  uploadsBuilt: boolean;
  note: string;
}

export interface HygienePerson {
  id: number;
  name: string;
  email: string;
  role: Role;
}

export interface AccountHygiene {
  neverSignedIn: number;
  temporaryPassword: number;
  disabled: number;
  duplicateEmployeeIds: { value: string; accounts: HygienePerson[] }[];
  duplicateContacts: { value: string; accounts: HygienePerson[] }[];
  missingAssignments: { account: HygienePerson; problem: string }[];
}

export interface InactiveAccount {
  id: number;
  name: string;
  email: string;
  role: Role;
  department: string | null;
  lastLoginAt: string | null;
  createdAt: string;
}

export interface ImportHistoryEntry {
  id: number;
  importedAt: string;
  administrator: string;
  rowsInFile: number;
  createdCount: number;
  rejectedRows: number;
  outcome: "CREATED" | "REJECTED";
  hasReport: boolean;
}

export interface YearReadiness {
  yearId: number;
  name: string;
  active: boolean;
  mode: "OPENING" | "CLOSING";
  byState: Record<string, number>;
  queryRaised: number;
  items: { code: string; level: HealthLevel; message: string }[];
  ready: boolean;
}

export interface HandoverNote {
  key: string;
  question: string;
  value: string;
  updatedBy: string | null;
  updatedAt: string | null;
}
