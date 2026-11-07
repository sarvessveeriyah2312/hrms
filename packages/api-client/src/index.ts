/**
 * Minimal typed client for the HRMS API. Works in the browser and in React Native (global fetch).
 * Tokens are supplied by the caller (web: in memory + HttpOnly refresh cookie; mobile: SecureStore).
 */

export type PunchType = 'IN' | 'OUT';
export type PunchResult = 'ACCEPTED' | 'FLAGGED' | 'BLOCKED';
export type PunchSource = 'MOBILE' | 'KIOSK' | 'WEB' | 'OFFLINE';

export interface ClockRequest {
  type: PunchType;
  lat?: number;
  lng?: number;
  accuracyM?: number;
  wifiBssid?: string;
  clientTime?: string; // ISO-8601
  mockLocation?: boolean;
  rootedOrJailbroken?: boolean;
  developerMode?: boolean;
  selfieFileId?: string;
  remarks?: string;
  source?: PunchSource;
}

export interface ClockResponse {
  id: string;
  result: PunchResult;
  serverTime: string;
  siteId: string | null;
  flags: string[];
  distanceM: number | null;
  code: string | null;
}

/** How far a permission reaches, narrowest first. */
export type DataScope = 'SELF' | 'TEAM' | 'DEPARTMENT' | 'ENTITY' | 'TENANT';
export const DATA_SCOPES: DataScope[] = ['SELF', 'TEAM', 'DEPARTMENT', 'ENTITY', 'TENANT'];

export interface Me {
  userId: string;
  employeeId: string | null;
  tenantId: string;
  companyCode: string;
  companyName: string;
  name: string | null;
  email: string | null;
  roles: string[];
  /** e.g. "leave:approve". Permissions of switched-off modules are already removed. */
  permissions: string[];
  /** Permission → widest granted scope. */
  scopes: Record<string, DataScope>;
  /** Modules this company has switched on (plus the always-on ones). */
  modules: string[];
  /** Signed in with a temporary password an admin set: they must choose their own before using the app. */
  mustChangePassword?: boolean;
}

/** Returned by login and refresh. The refresh token itself is an HttpOnly cookie, never in this body. */
export interface Session {
  accessToken: string;
  /** Seconds until the access token expires. */
  expiresIn: number;
  user: Me;
}

/** RFC 9457 Problem Details as returned by the API. */
export interface Problem {
  status: number;
  title?: string;
  detail?: string;
  code?: string;
  fields?: Record<string, string>;
  /** With code COMPANY_REQUIRED: the companies the password matched. */
  companies?: { code: string; name: string }[];
  /** With codes ROSTER_HAS_ERRORS / ROSTER_HAS_WARNINGS: what to fix or confirm. */
  problems?: RosterProblem[];
}

// ---------- directory and administration ----------

export interface EmployeeSummary {
  id: string;
  employeeNo: string;
  fullName: string;
  email: string | null;
  department: string | null;
  manager: string | null;
  status: string;
  jobTitle?: string | null;
  hasPhoto?: boolean;
}

export interface CatalogAction {
  key: string;
  name: string;
  description: string;
  scopes: DataScope[];
}

export interface CatalogModule {
  key: string;
  name: string;
  licensable: boolean;
  actions: CatalogAction[];
}

export interface AdminUser {
  userId: string;
  employeeId: string | null;
  fullName: string | null;
  email: string | null;
  phone: string | null;
  employeeNo: string | null;
  departmentId: string | null;
  department: string | null;
  managerId: string | null;
  manager: string | null;
  backupApproverId: string | null;
  backupApprover: string | null;
  status: 'ACTIVE' | 'DISABLED';
  lastLoginAt: string | null;
  roleIds: string[];
  roles: string[];
}

export interface AdminUserInput {
  fullName: string;
  email?: string | null;
  phone?: string | null;
  employeeNo: string;
  departmentId?: string | null;
  managerId?: string | null;
  backupApproverId?: string | null;
  joinDate?: string | null;
  roleIds: string[];
  status?: 'ACTIVE' | 'DISABLED';
}

export interface AdminRole {
  id: string;
  code: string;
  name: string;
  description: string | null;
  system: boolean;
  userCount: number;
  permissions: Record<string, DataScope>;
}

export interface AdminRoleInput {
  name: string;
  description?: string | null;
  permissions: Record<string, DataScope>;
}

export interface CompanySettings {
  name: string;
  code: string;
  country: string;
  modules: { key: string; name: string; enabled: boolean }[];
}

export interface Department {
  id: string;
  name: string;
  employeeCount: number;
  /** People in the department who manage someone. */
  managerCount: number;
  /** Approval workflows whose conditions name this department. */
  workflowCount: number;
}

export interface DepartmentMember {
  employeeId: string;
  fullName: string;
  employeeNo: string;
  email: string | null;
  manager: string | null;
  managesOthers: boolean;
  status: string;
}

// ---------- approvals ----------

export interface InboxItem {
  instanceId: string;
  subjectType: string;
  subjectId: string;
  module: string;
  title: string;
  detail: string;
  tone: string;
  requester: string;
  requesterNo: string;
  submittedAt: string;
  stepLabel: string;
  /** ASSIGNEE, BACKUP or HR: waiting for you. OVERRIDE: waiting for someone else, but you may step in. */
  actingAs: 'ASSIGNEE' | 'BACKUP' | 'HR' | 'OVERRIDE';
  waitingFor: string;
  dueAt: string | null;
  overdue: boolean;
}

export interface TimelineStep {
  level: number;
  label: string;
  approver: string;
  state: 'WAITING' | 'ACTIVE' | 'DONE' | 'SKIPPED';
  action: 'APPROVE' | 'REJECT' | null;
  actedBy: string | null;
  actedAs: string | null;
  comment: string | null;
  actedAt: string | null;
}

// ---------- leave ----------

export type DayPart = 'FULL' | 'AM' | 'PM';
export type LeaveStatus = 'DRAFT' | 'PENDING' | 'APPROVED' | 'REJECTED' | 'RETURNED' | 'CANCELLED';

export interface FileMeta {
  id: string;
  name: string;
  contentType: string;
  sizeBytes: number;
}

export interface LeaveType {
  id: string;
  code: string;
  name: string;
  tone: string;
  paid: boolean;
  trackBalance: boolean;
  defaultDays: number;
  halfDayAllowed: boolean;
  approvalLevels: number;
  hrApproval: boolean;
  active: boolean;
  /** In the joining year: days × months left ÷ 12, rounded down to a half day. */
  proRate: boolean;
  /** Most unused days the year-end run carries forward (0 = none). */
  carryMax: number;
  /** "After N years of service, D days" — the highest tier reached on 1 January applies. */
  serviceTiers: { minYears: number; days: number }[];
  /** Carried days must be used by the end of this month (1–12); null means they never expire. */
  carryExpiryMonth: number | null;
  /** A supporting document (e.g. medical certificate) is needed to apply. */
  requiresAttachment: boolean;
}

export interface LeaveBalance {
  leaveTypeId: string;
  name: string;
  tone: string;
  trackBalance: boolean;
  entitled: number;
  carried: number;
  used: number;
  pending: number;
  /** null when the type has no balance (e.g. unpaid leave). */
  available: number | null;
  /** POLICY: from the type's rules. MANUAL: set by HR for this person. */
  source: 'POLICY' | 'MANUAL';
  /** The entitled figure in words, e.g. "2+ years of service" or "Set by HR: Contract". */
  basis: string;
  /** What the rules alone would give, for comparison with a manual figure. */
  policyDays: number;
  carriedSource: 'MANUAL' | 'YEAR_END' | null;
  note: string | null;
  /** Carried days lost because they weren't used by the expiry date. */
  carriedExpired: number;
  carriedExpiresOn: string | null;
  /** Days in the person's planner (drafts): not sent, not taken off the balance. */
  planned: number;
  /** Leave earned this year on top of the entitlement (e.g. time off for overtime) that still counts. */
  earned: number;
  /** Earned leave that expired unused. */
  earnedExpired: number;
}

export interface YearEndResult {
  year: number;
  people: number;
  days: number;
  keptManual: number;
  types: string[];
}

export interface Holiday {
  id: string;
  date: string;
  name: string;
}

export interface LeaveRequest {
  id: string;
  employeeId: string;
  employeeName: string;
  leaveTypeId: string;
  typeName: string;
  tone: string;
  startDate: string;
  endDate: string;
  dayPart: DayPart;
  days: number;
  reason: string | null;
  status: LeaveStatus;
  createdAt: string;
  decidedAt: string | null;
  workflowId: string | null;
  pendingWith: { level: number; totalSteps: number; label: string; approver: string } | null;
  canCancel: boolean;
  /** Sent back for changes: the employee can edit and resubmit. */
  canEdit: boolean;
  attachment: FileMeta | null;
  /** Name of the HR person who recorded it for the employee. */
  recordedBy: string | null;
  returnedComment: string | null;
  cancelReason: string | null;
}

export interface LeaveApplyInput {
  leaveTypeId: string;
  startDate: string;
  endDate: string;
  dayPart: DayPart;
  reason?: string;
  attachmentId?: string | null;
}

export type FlowStepKind = 'MANAGER' | 'PERSON' | 'ROLE' | 'HR';

export interface FlowStep {
  kind: FlowStepKind;
  /** MANAGER: 1 = their manager, 2 = that manager's manager, 3 = one more up. */
  managerLevel?: number | null;
  employeeId?: string | null;
  roleId?: string | null;
}

/** What a workflow is for. */
export type FlowSubject = 'LEAVE' | 'CLAIM' | 'CORRECTION' | 'OVERTIME' | 'SHIFT_CHANGE';

/**
 * Choosing a workflow for a type. Changes: SWITCH_ON, ADD_TYPE, REMOVE_TYPE (from flow), MOVE_ABOVE (flow).
 * Warnings: OTHER_TYPES, ONLY_SOME, UNUSED (flow). No changes: it's already the one used.
 */
export interface FlowAssignment {
  flowId: string;
  changes: { kind: 'SWITCH_ON' | 'ADD_TYPE' | 'REMOVE_TYPE' | 'MOVE_ABOVE'; flow: string }[];
  warnings: { kind: 'OTHER_TYPES' | 'ONLY_SOME' | 'UNUSED'; flow: string }[];
  applied: boolean;
}

export interface ApprovalFlow {
  id: string;
  subjectType: FlowSubject;
  name: string;
  description: string | null;
  active: boolean;
  /** Checking order: the first active match wins. */
  position: number;
  /** Leave types or claim types; empty means any. */
  typeIds: string[];
  departmentIds: string[];
  /** Leave: working days. Claims: the claim amount (in the claimant's currency). */
  minQuantity: number | null;
  maxQuantity: number | null;
  steps: FlowStep[];
  updatedAt: string;
}

export type ApprovalFlowInput = Omit<ApprovalFlow, 'id' | 'subjectType' | 'position' | 'updatedAt'>;

export interface ApprovalPreview {
  workflow: string;
  /** No configured workflow matched; the leave type's own approval setting is used. */
  fromLeaveType: boolean;
  steps: { position: number; kind: FlowStepKind; approver: string; skipped: boolean; note: string }[];
}

export interface ClaimApprovalPreview {
  workflow: string;
  /** No configured workflow matched; the claim type's own approval setting is used. */
  fromClaimType: boolean;
  steps: ApprovalPreview['steps'];
}

export interface AppNotification {
  id: string;
  kind: 'APPROVAL_WAITING' | 'REQUEST_APPROVED' | 'REQUEST_REJECTED' | 'REQUEST_RETURNED' | 'REQUEST_CANCELLED' | 'LEAVE_RECORDED' | 'CLAIM_PAID';
  title: string;
  body: string | null;
  /** Path inside the web app to open, e.g. /app/approvals?open=<id>. */
  link: string;
  tone: string;
  createdAt: string;
  readAt: string | null;
}

export interface LeaveReport {
  year: number;
  scope: DataScope;
  lines: {
    employeeId: string; employeeNo: string; employeeName: string; department: string | null; leaveTypeId: string;
    leaveType: string; entitled: number | null; carried: number | null; used: number; pending: number;
    available: number | null; basis: string;
  }[];
  totals: { leaveTypeId: string; leaveType: string; entitled: number; used: number; pending: number; people: number }[];
}

export type LeaveTypeInput = Partial<Omit<LeaveType, 'id' | 'code'>> & { name: string };

// ---------- claims ----------

export type ClaimStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'RETURNED' | 'CANCELLED';
/** RECEIPT: the amount is typed in from a receipt. MILEAGE: distance × the type's rate per km. */
export type ClaimKind = 'RECEIPT' | 'MILEAGE';

export interface ClaimType {
  id: string;
  code: string;
  name: string;
  tone: string;
  kind: ClaimKind;
  /** null: no limit. */
  maxPerClaim: number | null;
  /** Per person, by the month of the expense. null: no limit. */
  maxPerMonth: number | null;
  /** MILEAGE only. */
  ratePerKm: number | null;
  /** RECEIPT types: a photo or PDF of the receipt is needed. */
  receiptRequired: boolean;
  approvalLevels: number;
  hrApproval: boolean;
  active: boolean;
}

export type ClaimTypeInput = Omit<ClaimType, 'id' | 'code' | 'active'> & { active?: boolean };

export interface Claim {
  id: string;
  employeeId: string;
  employeeName: string;
  employeeNo: string;
  claimTypeId: string;
  typeName: string;
  tone: string;
  kind: ClaimKind;
  expenseDate: string;
  description: string;
  merchant: string | null;
  amount: number;
  /** ISO 4217, from the employee's legal entity. */
  currency: string;
  distanceKm: number | null;
  routeFrom: string | null;
  routeTo: string | null;
  receipt: FileMeta | null;
  status: ClaimStatus;
  createdAt: string;
  decidedAt: string | null;
  workflowId: string | null;
  pendingWith: { level: number; totalSteps: number; label: string; approver: string } | null;
  canCancel: boolean;
  canEdit: boolean;
  returnedComment: string | null;
  /** Set when HR marked the approved claim as paid. */
  paidAt: string | null;
  paidBy: string | null;
  paymentRef: string | null;
}

export interface ClaimInput {
  claimTypeId: string;
  expenseDate: string;
  description: string;
  merchant?: string | null;
  /** RECEIPT types. */
  amount?: number | null;
  /** MILEAGE types. */
  distanceKm?: number | null;
  routeFrom?: string | null;
  routeTo?: string | null;
  receiptId?: string | null;
}

export interface ClaimUsage {
  claimTypeId: string;
  name: string;
  tone: string;
  maxPerMonth: number | null;
  approved: number;
  pending: number;
  /** null when the type has no monthly limit. */
  left: number | null;
}

export type ClaimFilter = 'PENDING' | 'APPROVED' | 'UNPAID' | 'PAID' | 'REJECTED';

/**
 * Claims report: approved and waiting claims by expense date, within your reports scope. One row per person,
 * claim type, currency and month; roll them up as needed. Amounts stay in each claimant's currency.
 */
export interface ClaimsReport {
  year: number;
  /** 1–12 when the report is for one month. */
  month: number | null;
  scope: DataScope;
  rows: ClaimsReportRow[];
}

export interface ClaimsReportRow {
  employeeId: string; employeeNo: string; employeeName: string; department: string | null;
  claimTypeId: string; claimType: string; currency: string; month: number;
  claims: number; approved: number; pending: number; paid: number; toPay: number;
}

// ---------- search ----------

export type SearchKind = 'PERSON' | 'LEAVE' | 'CLAIM' | 'USER' | 'ROLE' | 'DEPARTMENT' | 'LEAVE_TYPE' | 'CLAIM_TYPE' | 'WORKFLOW' | 'HOLIDAY';

/** One search result. Raw values (status, dates, amount) are formatted by the app in the reader's language. */
export interface SearchHit {
  kind: SearchKind;
  id: string;
  title: string;
  /** Extra text that matched or helps tell results apart (number, department, description…). */
  meta: string | null;
  /** LEAVE/CLAIM: the request status (claims: PAID once paid). USER: ACTIVE/DISABLED. Types: RETIRED. WORKFLOW: OFF. */
  status: string | null;
  date: string | null;
  endDate: string | null;
  amount: number | null;
  currency: string | null;
  /** WORKFLOW: LEAVE or CLAIM. */
  tag: string | null;
  /** Path in the web app that opens it. */
  link: string;
}

// ---------- attendance sites ----------

/** GEOFENCE / WIFI prove someone is at the site; KIOSK is the site's shared tablet; SELFIE / FACE are extra checks. */
export type ClockMethod = 'GEOFENCE' | 'WIFI' | 'KIOSK' | 'SELFIE' | 'FACE';
/** With both geofence and Wi-Fi on: is either enough, or are both needed? */
export type WifiMode = 'GPS_OR_WIFI' | 'GPS_AND_WIFI';

export interface SiteWifi { bssid: string; ssid: string | null; label: string | null }

export interface SiteSummary {
  id: string; name: string; address: string | null; entity: string; active: boolean;
  lat: number; lng: number; radiusM: number; methods: ClockMethod[]; wifiMode: WifiMode; wifiCount: number; people: number;
}

export interface SiteMember { employeeId: string; employeeNo: string; fullName: string; department: string | null; otherSites: string[] }

export interface SiteDetail {
  id: string; name: string; address: string | null; entityId: string; entity: string; active: boolean;
  lat: number; lng: number; radiusM: number; methods: ClockMethod[]; wifiMode: WifiMode; remarksRequired: boolean;
  wifi: SiteWifi[]; members: SiteMember[]; updatedAt: string;
  /** People have clocked in here: it can be switched off but not deleted. */
  hasPunches: boolean;
  /** Working hours ("09:00:00"), until shifts arrive; clock-ins after start + grace minutes are late. */
  startTime: string; endTime: string; lateGraceMin: number;
}

/** A test clock-in's outcome (nothing saved). code/message explain a BLOCKED one. */
export interface ClockCheck {
  result: PunchResult; code: string | null; message: string | null;
  site: string | null; distanceM: number | null; radiusM: number | null; flags: string[];
}

/** One of your sites, as the phone needs it to check before clocking in. */
export interface MySite {
  id: string; name: string; address: string | null; lat: number; lng: number; radiusM: number; methods: ClockMethod[];
  wifiMode: WifiMode; remarksRequired: boolean; selfieRequired: boolean; wifiBssids: string[];
}

export interface SiteInput {
  name: string; address?: string | null; entityId?: string | null; active: boolean; lat: number; lng: number; radiusM: number;
  methods: ClockMethod[]; wifiMode: WifiMode; remarksRequired: boolean; wifi: SiteWifi[];
  /** "HH:mm". */
  startTime: string; endTime: string; lateGraceMin: number;
}

// ---------- attendance today ----------

export type DayStatus = 'IN' | 'LATE' | 'NOT_IN' | 'ON_LEAVE' | 'OFF';

/** One person's day. Times are local to their legal entity ("09:12:00"). */
export interface TodayPerson {
  employeeId: string; employeeNo: string; fullName: string; department: string | null;
  /** Where they clocked in, else the first site they belong to. */
  siteId: string | null; site: string | null;
  status: DayStatus;
  /** When they were expected (start of day, or midday with morning leave); null when not expected. */
  expectedAt: string | null;
  firstIn: string | null;
  /** Their last clock-out, when they've clocked out. */
  lastOut: string | null;
  /** Clocked in and not out again. */
  stillIn: boolean;
  /** Late clock-ins, or how long overdue someone not in yet is. */
  minutesLate: number;
  /** Not in, and past the expected time plus grace. */
  overdue: boolean;
  /** A clock-in today was flagged by the rules (e.g. outside the geofence on a lenient site). */
  flagged: boolean;
  leaveType: string | null; leavePart: 'FULL' | 'AM' | 'PM' | null;
  offReason: 'HOLIDAY' | 'REST_DAY' | null;
}

export interface Today {
  date: string; isToday: boolean; scope: DataScope; holiday: string | null;
  sites: { id: string; name: string; startTime: string; endTime: string; graceMin: number }[];
  people: TodayPerson[];
  /** More people than the page can show; narrow the scope. */
  truncated: boolean;
}

// ---------- timesheets ----------

/** A clock-in and its clock-out ("09:02:00"); out is null while still in, or when the clock-out was missed. */
export interface WorkSession { in: string; out: string | null }

/** One day of a timesheet. Times are local to the person's legal entity. */
export interface TimesheetDay {
  date: string; status: DayStatus;
  /** Expected, didn't clock in, and the day is over. */
  absent: boolean;
  offReason: 'HOLIDAY' | 'REST_DAY' | null; holiday: string | null;
  leaveType: string | null; leavePart: 'FULL' | 'AM' | 'PM' | null;
  siteId: string | null; site: string | null;
  expectedIn: string | null; expectedOut: string | null;
  sessions: WorkSession[]; firstIn: string | null; lastOut: string | null;
  workedMin: number; lateMin: number; earlyMin: number;
  missedOut: boolean; stillIn: boolean; flagged: boolean;
  overdue: boolean; overdueMin: number;
  /** An approved correction changed this day's times. */
  corrected: boolean;
  /** The day's latest correction request, if any. */
  correction: { id: string; status: CorrectionStatus; clockIn: string | null; clockOut: string | null } | null;
  /** Approved overtime on the day, any rate. */
  overtimeMin: number;
  /** The published shifts that day (they set the expected hours). */
  shifts: { start: string; end: string; code: string | null; breakMin: number }[];
  /** Time worked beyond the day's shifts (their paid time): something to ask overtime for. */
  extraMin: number;
}

// ---------- employee profile ----------
export type ProfileFieldType = 'TEXT' | 'EMAIL' | 'PHONE' | 'DATE' | 'YEAR' | 'SELECT';
export interface ProfileField {
  key: string;
  type: ProfileFieldType;
  /** Only required once anything in the section (or list entry) is filled in. */
  required: boolean;
  max: number;
  options: string[];
  /** Stored encrypted (ID, tax and bank account numbers). */
  sensitive: boolean;
}
/** list: holds { items: [...] } up to maxItems. isPrivate: only the person and HR see it. */
export interface ProfileSectionSpec {
  key: string;
  list: boolean;
  maxItems: number;
  isPrivate: boolean;
  fields: ProfileField[];
}
/** Object sections: field → value. List sections: { items: [{ field → value }] }. */
export type SectionData = Record<string, unknown>;
export type EmploymentType = 'PERMANENT' | 'CONTRACT' | 'PART_TIME' | 'INTERN' | 'TEMPORARY';
export interface JobDetails {
  fullName: string;
  joinDate: string;
  jobTitle: string | null;
  employmentType: EmploymentType | null;
  confirmationDue: string | null;
  confirmedOn: string | null;
  resignedOn: string | null;
  lastDay: string | null;
}
export type ChangeStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'RETURNED' | 'CANCELLED';
export interface ProfileChange {
  id: string;
  employeeId: string;
  employeeName: string;
  employeeNo: string;
  section: string;
  /** Field keys that differ; "items" for a list section that changed. */
  changedFields: string[];
  note: string | null;
  status: ChangeStatus;
  returnedComment: string | null;
  createdAt: string;
  decidedAt: string | null;
  pendingWith: { level: number; totalSteps: number; label: string; approver: string } | null;
  canCancel: boolean;
  canEdit: boolean;
}
/** current: the section when it was sent; live: as it is now. */
export interface ProfileChangeDetail {
  change: ProfileChange;
  current: SectionData;
  proposed: SectionData;
  live: SectionData;
  timeline: TimelineStep[];
}
export interface EmployeeProfile {
  id: string;
  employeeNo: string;
  email: string | null;
  department: string | null;
  manager: string | null;
  entity: string;
  country: string;
  status: string;
  job: JobDetails;
  hasPhoto: boolean;
  /** Only the sections you may see. */
  sections: Record<string, SectionData>;
  hiddenSections: string[];
  access: { self: boolean; manage: boolean; seePrivate: boolean };
  openChanges: ProfileChange[];
}
export type DocumentCategory = 'CONTRACT' | 'IDENTITY' | 'CERTIFICATE' | 'LETTER' | 'MEDICAL' | 'OTHER';
export interface EmployeeDocument {
  id: string;
  employeeId: string;
  category: DocumentCategory;
  description: string | null;
  file: FileMeta | null;
  uploadedBy: string | null;
  createdAt: string;
  canDelete: boolean;
}
export type AssetKind = 'LAPTOP' | 'PHONE' | 'ACCESS_CARD' | 'VEHICLE' | 'UNIFORM' | 'OTHER';
export interface EmployeeAsset {
  id: string;
  employeeId: string;
  kind: AssetKind;
  name: string;
  tag: string | null;
  issuedOn: string;
  returnedOn: string | null;
  note: string | null;
}
export type AssetInput = Omit<EmployeeAsset, 'id' | 'employeeId'>;

// ---------- payroll ----------
export type PayrollKind = 'PAYSLIP' | 'TAX_FORM';
export type TaxFormCode = 'EA' | 'IR8A' | 'IR56B' | 'FORM16' | 'OTHER';
/** period: "2026-10" for payslips, "2025" for tax forms. firstViewedAt: the employee's read receipt. */
export interface PayrollDocument {
  id: string;
  employeeId: string;
  employeeName: string;
  employeeNo: string;
  department: string | null;
  kind: PayrollKind;
  period: string;
  formCode: TaxFormCode | null;
  fileName: string | null;
  sizeBytes: number;
  source: 'UPLOAD' | 'GENERATED';
  currency: string | null;
  gross: number | null;
  deductions: number | null;
  net: number | null;
  employerCost: number | null;
  status: 'DRAFT' | 'PUBLISHED';
  publishedAt: string | null;
  firstViewedAt: string | null;
  lastViewedAt: string | null;
  viewCount: number;
  createdAt: string;
  /** The layout a generated payslip was drawn with. */
  template: string | null;
}
export interface PayLine {
  kind: 'EARNING' | 'DEDUCTION' | 'EMPLOYER';
  /** Earnings only: basic pay, an allowance or overtime. */
  category: 'BASIC' | 'ALLOWANCE' | 'OVERTIME' | null;
  label: string;
  amount: number;
  /** Overtime hours (or basic days) and the rate, when given. */
  quantity: number | null;
  rate: number | null;
}
/** A payslip layout. preview: the design's image file name (shown as a thumbnail). */
export interface PayslipTemplate {
  code: string;
  name: string;
  description: string;
  preview: string | null;
}
export interface PayrollPeriodSummary {
  period: string;
  formCode: TaxFormCode | null;
  total: number;
  drafts: number;
  published: number;
  read: number;
}
export interface PayrollPerson {
  employeeId: string;
  employeeNo: string;
  employeeName: string;
  department: string | null;
}
export interface PayrollPeriodView {
  kind: PayrollKind;
  period: string;
  formCode: TaxFormCode | null;
  documents: PayrollDocument[];
  /** People employed during the period with no document for it. */
  missing: PayrollPerson[];
}
export interface PayrollUploadResult {
  saved: PayrollDocument[];
  failed: { fileId: string | null; name: string | null; message: string }[];
}
export interface PayrollExportRow {
  employeeId: string;
  employeeNo: string;
  employeeName: string;
  department: string | null;
  workingDays: number;
  daysWorked: number;
  hoursWorked: number;
  lateCount: number;
  lateMinutes: number;
  earlyCount: number;
  missedClockOuts: number;
  absentDays: number;
  leaveDays: number;
  paidOvertimeHours: number;
  weightedOvertimeHours: number;
  timeOffOvertimeHours: number;
  overtimeByRate: Record<string, number>;
  claims: Record<string, number>;
  claimsUnpaid: Record<string, number>;
}
export interface PayrollExportSummary {
  month: string;
  locked: boolean;
  truncated: boolean;
  waiting: { corrections: number; overtime: number; claims: number };
  rates: string[];
  currencies: string[];
  rows: PayrollExportRow[];
}
export interface PayrollDashboardMonth {
  month: string;
  headcount: number;
  joiners: number;
  leavers: number;
  /** Currency → gross pay plus employer contributions, from payslips that carry amounts. */
  cost: Record<string, number>;
  payslips: number;
  claims: Record<string, number>;
  overtimeHours: number;
}
export interface PayrollDashboard {
  year: number;
  headcountNow: number;
  joiners: number;
  leavers: number;
  turnover: number | null;
  months: PayrollDashboardMonth[];
  departments: { name: string; headcount: number }[];
  costThisYear: Record<string, number>;
}

// ---------- communication ----------
export type NoticeKind = 'ANNOUNCEMENT' | 'MEMO' | 'POLICY';
export type NoticeAudience = 'ALL' | 'DEPARTMENTS' | 'ENTITIES';
export interface Notice {
  id: string;
  kind: NoticeKind;
  title: string;
  summary: string | null;
  body: string;
  audience: NoticeAudience;
  audienceIds: string[];
  audienceNames: string[];
  attachments: FileMeta[];
  pinned: boolean;
  requiresAck: boolean;
  version: number;
  status: 'DRAFT' | 'PUBLISHED' | 'ARCHIVED';
  publishedAt: string | null;
  publishedBy: string | null;
  createdAt: string;
  updatedAt: string;
  /** Your own receipt for the current version. */
  readAt: string | null;
  acknowledgedAt: string | null;
  /** For people who manage it (published ones). */
  audienceSize: number | null;
  readCount: number | null;
  acknowledgedCount: number | null;
}
export interface NoticeInput {
  kind: NoticeKind;
  title: string;
  summary?: string | null;
  body: string;
  audience: NoticeAudience;
  audienceIds: string[];
  attachmentIds: string[];
  pinned: boolean;
  requiresAck: boolean;
}
export interface NoticeReceipts {
  noticeId: string;
  version: number;
  requiresAck: boolean;
  people: { employeeId: string; employeeNo: string; name: string; department: string | null; readAt: string | null; acknowledgedAt: string | null }[];
}
export type LetterKind = 'OFFER' | 'CONFIRMATION' | 'INCREMENT' | 'PROMOTION' | 'EXPERIENCE' | 'WARNING' | 'OTHER';
export interface Letterhead {
  companyName: string | null;
  address: string | null;
  phone: string | null;
  email: string | null;
  website: string | null;
  registrationNo: string | null;
  logo: FileMeta | null;
  signatoryName: string | null;
  signatoryTitle: string | null;
  footer: string | null;
}
export type LetterheadInput = Omit<Letterhead, 'logo'> & { logoFileId: string | null };
/** fields: the placeholders typed in when issuing (the others come from the person's record). */
export interface LetterTemplate {
  id: string;
  kind: LetterKind;
  name: string;
  subject: string;
  body: string;
  active: boolean;
  fields: string[];
  updatedAt: string;
}
export type LetterTemplateInput = Pick<LetterTemplate, 'kind' | 'name' | 'subject' | 'body' | 'active'>;
export interface IssuedLetter {
  id: string;
  employeeId: string;
  employeeName: string;
  employeeNo: string;
  kind: LetterKind;
  subject: string;
  fileName: string | null;
  issuedBy: string | null;
  issuedAt: string;
  firstViewedAt: string | null;
  withdrawnAt: string | null;
}
export type GrievanceCategory = 'WORKPLACE' | 'HARASSMENT' | 'PAY' | 'MANAGEMENT' | 'FACILITIES' | 'SUGGESTION' | 'OTHER';
export type GrievanceStatus = 'OPEN' | 'IN_PROGRESS' | 'RESOLVED' | 'CLOSED';
export interface Grievance {
  id: string;
  category: GrievanceCategory;
  subject: string;
  anonymous: boolean;
  status: GrievanceStatus;
  /** "Anonymous" to handlers when the person chose that. */
  raisedBy: string;
  raisedByNo: string | null;
  assignedTo: string | null;
  assignedName: string | null;
  createdAt: string;
  updatedAt: string;
  closedAt: string | null;
  role: 'SUBMITTER' | 'HANDLER';
  canReopen: boolean;
  messages: number;
}
export interface GrievanceMessage {
  id: string;
  author: string;
  authorRole: 'SUBMITTER' | 'HANDLER' | 'SYSTEM';
  mine: boolean;
  body: string;
  file: FileMeta | null;
  createdAt: string;
}
export interface GrievanceDetail {
  grievance: Grievance;
  messages: GrievanceMessage[];
}
export interface NotificationPreference {
  kind: string;
  email: boolean;
  push: boolean;
}

// ---------- attendance corrections ----------

export type CorrectionStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'RETURNED' | 'CANCELLED';

/** Asks for a day's first clock-in and/or last clock-out to be set ("HH:mm", local to the person). */
export interface CorrectionInput { date: string; clockIn: string | null; clockOut: string | null; reason: string }

export interface AttendanceCorrection {
  id: string; employeeId: string; employeeName: string; employeeNo: string;
  date: string; clockIn: string | null; clockOut: string | null; reason: string;
  status: CorrectionStatus; returnedComment: string | null; createdAt: string; decidedAt: string | null;
  pendingWith: { level: number; totalSteps: number; label: string; approver: string } | null;
  canCancel: boolean; canEdit: boolean;
}

export interface CorrectionDetail {
  correction: AttendanceCorrection;
  /** What the clock-ins showed that day, before any correction. */
  recorded: { firstIn: string | null; lastOut: string | null; sessions: WorkSession[] };
  timeline: TimelineStep[];
}

// ---------- overtime ----------

export type OvertimeStatus = CorrectionStatus;

/** The kind of day overtime is worked on, which sets its rate. */
export type OvertimeDayKind = 'WORKING' | 'REST' | 'HOLIDAY';

export type OvertimeCompensation = 'PAY' | 'TIME_OFF';

/**
 * Extra hours worked on a day (quarter hours). rate: needed when paid, or when the rule applies it to time off.
 * compensation: needed only when the rule lets the employee choose.
 */
export interface OvertimeInput { date: string; hours: number; rate: number | null; reason: string; compensation: OvertimeCompensation | null }

export interface OvertimeRequest {
  id: string; employeeId: string; employeeName: string; employeeNo: string;
  date: string; hours: number; rate: number | null; reason: string;
  status: OvertimeStatus; returnedComment: string | null; createdAt: string; decidedAt: string | null;
  pendingWith: { level: number; totalSteps: number; label: string; approver: string } | null;
  canCancel: boolean; canEdit: boolean;
  compensation: OvertimeCompensation;
  /** Once approved as time off: the hours that counted, and the leave given (0 while the hours wait in the bank). */
  creditedHours: number | null; creditedDays: number | null; leaveType: string | null;
  /** The kind of day it was sent for (null on requests from before rates were set by rules). */
  dayKind: OvertimeDayKind | null;
  /** Asked for before the day it's for. */
  planned: boolean;
}

/** What time off earned (done) or would earn if approved now. */
export interface OvertimeTimeOff {
  leaveTypeId: string; leaveType: string; countedHours: number; days: number;
  /** Banked hours after it (estimates for banked rules). */
  bankHours: number | null;
  /** Hours short of a whole unit that won't be given (per-request rules). */
  lostHours: number | null;
  conversion: OvertimeConversion | null;
  done: boolean;
}

export interface OvertimeDetail {
  overtime: OvertimeRequest;
  /** The day's clock-ins (with any approved correction), time worked, and other overtime asked for or approved that day. */
  recorded: { sessions: WorkSession[]; workedMin: number; otherHours: number };
  timeline: TimelineStep[];
  timeOff: OvertimeTimeOff | null;
}

// ---------- overtime rules ----------

/** PAY: paid. TIME_OFF: given back as leave. CHOICE: the employee picks on each request. */
export type OvertimeRuleCompensation = 'PAY' | 'TIME_OFF' | 'CHOICE';
/** BANKED: hours add up across requests. PER_REQUEST: each request converts on its own. */
export type OvertimeConversion = 'BANKED' | 'PER_REQUEST';
export type OvertimeExpiry = 'NONE' | 'DAYS' | 'YEAR_END';

export interface OvertimeRule {
  /** null for the built-in default (paid) when no rule is set. */
  id: string | null;
  scope: 'COMPANY' | 'ENTITY' | 'DEPARTMENT' | 'DEFAULT';
  entityId: string | null; entityName: string | null;
  departmentId: string | null; departmentName: string | null;
  compensation: OvertimeRuleCompensation;
  conversion: OvertimeConversion;
  creditUnit: 'HALF_DAY' | 'FULL_DAY';
  hoursPerUnit: number;
  applyRate: boolean;
  leaveTypeId: string | null; leaveTypeName: string | null;
  expiry: OvertimeExpiry;
  expiryDays: number | null;
  updatedAt: string | null;
  /** Rates for a working day, rest day and public holiday (1 to 5, quarter steps). */
  rateWorking: number; rateRest: number; rateHoliday: number;
  /** AUTO: the rate comes from the kind of day. EMPLOYEE: the employee picks one of the rule's rates. */
  rateMode: 'AUTO' | 'EMPLOYEE';
  /** Overtime has to be asked for before it's worked. */
  requirePreApproval: boolean;
}

export interface OvertimeRuleInput {
  entityId: string | null; departmentId: string | null;
  compensation: OvertimeRuleCompensation; conversion: OvertimeConversion; creditUnit: 'HALF_DAY' | 'FULL_DAY';
  hoursPerUnit: number; applyRate: boolean; leaveTypeId: string | null; expiry: OvertimeExpiry; expiryDays: number | null;
  rateWorking: number; rateRest: number; rateHoliday: number; rateMode: 'AUTO' | 'EMPLOYEE'; requirePreApproval: boolean;
}

export interface OvertimeRuleSettings {
  rules: OvertimeRule[];
  entities: { id: string; name: string }[];
  departments: { id: string; name: string }[];
  /** Leave types that keep a balance, so time off can be added to them. */
  leaveTypes: { id: string; name: string }[];
}

/** Approving several at once: the ones approved, and why the others weren't. */
export interface ApproveManyResult {
  approved: string[];
  failed: { instanceId: string; code: string; message: string }[];
}

// ---------- scheduling ----------

export type ShiftTone = 'blue' | 'green' | 'orange' | 'teal' | 'purple' | 'pink';

export interface ShiftTemplate {
  id: string; name: string; code: string; startTime: string; endTime: string;
  breakMin: number; graceMin: number; tone: ShiftTone; active: boolean;
}

export interface ShiftTemplateInput {
  name: string; code: string; startTime: string; endTime: string; breakMin: number; graceMin: number; tone: ShiftTone; active: boolean;
}

/** A shift in a site's working (draft) roster. Times are HH:mm(:ss), local. employeeId null: an open shift. */
export interface RosterShift {
  id: string; siteId: string; employeeId: string | null; date: string; startTime: string; endTime: string;
  breakMin: number; graceMin: number; templateId: string | null; code: string | null; tone: ShiftTone; note: string | null;
}

/** templateId fills in the times not given. */
export interface RosterShiftInput {
  siteId?: string; employeeId?: string | null; date?: string; templateId?: string | null;
  startTime?: string | null; endTime?: string | null; breakMin?: number | null; graceMin?: number | null; note?: string | null;
}

/** ERROR stops publishing; WARNING needs confirming. */
export interface RosterProblem {
  kind: 'OVERLAP' | 'TOO_MANY' | 'ON_LEAVE' | 'HOLIDAY' | 'REST_DAY' | 'UNAVAILABLE' | 'SHORT_REST';
  severity: 'ERROR' | 'WARNING';
  shiftId: string; employeeId: string; employeeName: string; date: string; detail: string | null;
}

export interface RosterWeek {
  site: { id: string; name: string };
  weekStart: string; days: string[];
  holidays: Record<string, string>;
  /** member: assigned to the site (others appear because they have shifts here). leave: by date. workDays: ISO 1–7. */
  people: {
    id: string; employeeNo: string; fullName: string; department: string | null; workDays: number[]; leave: Record<string, string>; member: boolean;
    /** Weekdays they've limited; the others are any time. */
    availability: AvailabilityDay[];
  }[];
  shifts: RosterShift[];
  /** changed: the working copy differs from what's published (or was never published). */
  status: { published: boolean; publishedAt: string | null; publishedBy: string | null; changed: boolean };
  problems: RosterProblem[];
  /** Some day of the week is in a month locked for payroll. */
  locked: boolean;
}

/** weekday ISO 1 (Monday) to 7. ANY: any time (the default); NONE: not available; WINDOWS: up to 3 windows (HH:mm). */
export interface AvailabilityDay {
  weekday: number; mode: 'ANY' | 'NONE' | 'WINDOWS'; windows: { start: string; end: string }[];
}

/** A published shift as shift requests see it (employeeId null: open). */
export interface PublishedShift {
  id: string; siteId: string; site: string; employeeId: string | null; employeeName: string | null; date: string;
  startTime: string; endTime: string; code: string | null; tone: ShiftTone;
}

export type ShiftRequestKind = 'GIVE' | 'SWAP' | 'CLAIM';
export type ShiftRequestStatus = 'COLLEAGUE' | 'PENDING' | 'APPROVED' | 'REJECTED' | 'RETURNED' | 'DECLINED' | 'CANCELLED';

/** mine: you asked. forMe: you're the colleague and it's waiting for you. */
export interface ShiftRequest {
  id: string; kind: ShiftRequestKind; status: ShiftRequestStatus; requesterId: string; requesterName: string;
  shift: PublishedShift | null; colleagueId: string | null; colleagueName: string | null; colleagueShift: PublishedShift | null;
  reason: string | null; returnedComment: string | null; createdAt: string;
  pendingWith: { level: number; totalSteps: number; label: string; approver: string } | null;
  mine: boolean; forMe: boolean;
}

/** A site's published roster as colleagues see it: names and times only. Shifts with employeeId null are open. */
export interface SiteRoster {
  sites: { id: string; name: string }[];
  site: { id: string; name: string } | null;
  weekStart: string; days: string[];
  people: { id: string; name: string }[];
  shifts: { id: string; employeeId: string | null; date: string; startTime: string; endTime: string; code: string | null; tone: ShiftTone }[];
}

/** A published shift on your own schedule. */
export interface MyShift {
  id: string; date: string; startTime: string; endTime: string; breakMin: number; code: string | null; tone: ShiftTone;
  note: string | null; siteId: string; site: string;
}

// ---------- attendance report and locked months ----------

/** One person and month of the attendance report. */
export interface AttendanceReportRow {
  employeeId: string; employeeNo: string; employeeName: string; department: string | null; site: string | null;
  /** YYYY-MM */
  month: string;
  totals: TimesheetTotals;
  /** Approved paid overtime, split by rate (rate null: sent before rates were required). */
  overtimePaidMin: number;
  paidByRate: { rate: number | null; minutes: number }[];
  /** Paid minutes × rate: what payroll pays for. */
  weightedMin: number;
  /** Approved overtime given back as leave. */
  overtimeTimeOffMin: number;
  locked: boolean;
}

export interface AttendanceReport {
  year: number; month: number | null; scope: DataScope; rows: AttendanceReportRow[];
  /** More people than one report shows; ask for a narrower scope. */
  truncated: boolean;
}

/** A month's lock for payroll. waiting*: requests for it still waiting (they can't be approved while it's locked). */
export interface PeriodLockStatus {
  month: string; locked: boolean; lockedBy: string | null; lockedAt: string | null;
  waitingCorrections: number; waitingOvertime: number;
  /** Only for people who can lock. */
  history: { action: 'LOCK' | 'UNLOCK'; reason: string | null; actor: string | null; at: string }[];
}

export interface TimesheetTotals {
  /** Working days without full-day leave. */
  expectedDays: number;
  daysWorked: number; workedMin: number;
  lateCount: number; lateMin: number; earlyCount: number; earlyMin: number;
  missedOut: number; absent: number;
  /** Approved leave on working days; half days count 0.5. */
  leaveDays: number;
  flagged: number;
  /** Approved overtime. */
  overtimeMin: number;
}

export interface Timesheet {
  employeeId: string; employeeNo: string; fullName: string; department: string | null;
  from: string; to: string; days: TimesheetDay[]; totals: TimesheetTotals;
}

export interface TeamTimesheets {
  from: string; to: string; scope: DataScope; truncated: boolean;
  people: { employeeId: string; employeeNo: string; fullName: string; department: string | null; site: string | null; totals: TimesheetTotals }[];
}

// ---------- company email (SMTP) ----------

export type MailSecurity = 'NONE' | 'STARTTLS' | 'SSL';

export interface MailSettings {
  enabled: boolean;
  host: string;
  port: number;
  security: MailSecurity;
  username: string | null;
  /** The password is never sent back; this says whether one is saved. */
  passwordSet: boolean;
  fromName: string | null;
  fromEmail: string;
  replyTo: string | null;
  updatedAt: string;
  updatedBy: string | null;
  lastTestAt: string | null;
  lastTestOk: boolean | null;
  lastTestError: string | null;
}

export interface MailSettingsView {
  /** null when the company hasn't set up its own server. */
  settings: MailSettings | null;
  /** Where the company's email goes now: its own server, the platform's, or the server log (not sent). */
  route: 'COMPANY' | 'PLATFORM' | 'LOG';
  /** False when the server has no secret key, so passwords can't be saved. */
  canStorePasswords: boolean;
  /** Local development: private hosts such as Mailpit on localhost:1025 are allowed. */
  privateHostsAllowed: boolean;
}

export interface MailSettingsInput {
  enabled: boolean;
  host: string;
  port: number;
  security: MailSecurity;
  username?: string | null;
  /** undefined/null keeps the saved password, '' removes it. */
  password?: string | null;
  fromName?: string | null;
  fromEmail: string;
  replyTo?: string | null;
}

export class ApiError extends Error {
  readonly problem: Problem;

  constructor(problem: Problem) {
    super(problem.detail ?? problem.title ?? `HTTP ${problem.status}`);
    this.problem = problem;
  }
}

export interface ApiClientOptions {
  /** Empty string means same origin (the web dev server proxies /api to the API). */
  baseUrl: string;
  getAccessToken: () => Promise<string | null> | string | null;
  /**
   * Called once when an authenticated request gets 401. Resolve true when a new access token is
   * available; the request is then retried once.
   */
  refreshAccessToken?: () => Promise<boolean>;
  /** Called when a request is still unauthorized (no refresh, or the refresh failed). */
  onUnauthorized?: () => void;
}

interface RequestOptions {
  /** JSON-serialisable, or FormData for uploads. */
  body?: unknown;
  /** Return the response as a Blob (downloads) instead of JSON. */
  blob?: boolean;
  idempotent?: boolean;
  /** Send the access token and refresh-and-retry on 401. Off for the auth endpoints themselves. */
  auth?: boolean;
  headers?: Record<string, string>;
}

/** Refresh and logout check this header; a cross-site form post can't set it. */
const XHR_HEADER = { 'X-Requested-With': 'hrms-web' };

function query(params: Record<string, string | number | undefined>): string {
  const parts = Object.entries(params).filter(([, v]) => v !== undefined && v !== '').map(([k, v]) => `${k}=${encodeURIComponent(String(v))}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

function newIdempotencyKey(): string {
  const c = globalThis.crypto as Crypto | undefined;
  if (c && typeof c.randomUUID === 'function') return c.randomUUID();
  return `${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

export function createApiClient(opts: ApiClientOptions) {
  async function request<T>(method: string, path: string, options: RequestOptions = {}): Promise<T> {
    const { body, idempotent = false, auth = true } = options;
    // One key per logical request, so a retry after refresh is recognised as the same request.
    const idempotencyKey = idempotent ? newIdempotencyKey() : undefined;

    const send = async () => {
      const headers: Record<string, string> = { Accept: 'application/json', ...options.headers };
      const form = typeof FormData !== 'undefined' && body instanceof FormData;
      if (body !== undefined && !form) headers['Content-Type'] = 'application/json';
      if (options.blob) headers.Accept = '*/*';
      if (idempotencyKey) headers['Idempotency-Key'] = idempotencyKey;
      const token = auth ? await opts.getAccessToken() : null;
      if (token) headers.Authorization = `Bearer ${token}`;
      return fetch(`${opts.baseUrl}/api/v1${path}`, {
        method,
        headers,
        credentials: 'include', // the refresh cookie (web); ignored by React Native
        body: body === undefined ? undefined : form ? (body as FormData) : JSON.stringify(body),
      });
    };

    let res = await send();
    if (res.status === 401 && auth) {
      const refreshed = opts.refreshAccessToken ? await opts.refreshAccessToken() : false;
      if (refreshed) res = await send();
      if (res.status === 401) opts.onUnauthorized?.();
    }
    if (!res.ok) {
      let problem: Problem = { status: res.status };
      try {
        problem = { status: res.status, ...(await res.json()) };
      } catch {
        // non-JSON error body
      }
      throw new ApiError(problem);
    }
    if (options.blob) return (await res.blob()) as T;
    return (res.status === 204 ? undefined : await res.json()) as T;
  }

  return {
    login: (username: string, password: string, companyCode?: string) =>
      request<Session>('POST', '/auth/login', { body: { username, password, companyCode }, auth: false }),
    refresh: () => request<Session>('POST', '/auth/refresh', { auth: false, headers: XHR_HEADER }),
    logout: () => request<void>('POST', '/auth/logout', { auth: false, headers: XHR_HEADER }),
    password: {
      /** Emails a reset link if an account matches. The answer is the same either way. */
      forgot: (login: string, companyCode?: string) =>
        request<void>('POST', '/auth/password/forgot', { body: { login, companyCode }, auth: false }),
      /** Which account a reset link is for (email partly hidden), or RESET_LINK_INVALID. */
      checkLink: (token: string) =>
        request<{ companyName: string; email: string }>('POST', '/auth/password/reset/check', { body: { token }, auth: false }),
      reset: (token: string, newPassword: string) =>
        request<void>('POST', '/auth/password/reset', { body: { token, newPassword }, auth: false }),
      /** Signed in: keeps this session, signs out the others, returns a fresh session. */
      change: (currentPassword: string, newPassword: string) =>
        request<Session>('POST', '/auth/password/change', { body: { currentPassword, newPassword } }),
    },
    me: () => request<Me>('GET', '/me'),
    clock: (req: ClockRequest) => request<ClockResponse>('POST', '/punches', { body: req, idempotent: true }),
    workflow: {
      flows: (subject: FlowSubject = 'LEAVE') => request<ApprovalFlow[]>('GET', `/workflow/flows${query({ subject })}`),
      createFlow: (subject: FlowSubject, input: ApprovalFlowInput) =>
        request<ApprovalFlow>('POST', `/workflow/flows${query({ subject })}`, { body: input }),
      updateFlow: (id: string, input: ApprovalFlowInput) => request<ApprovalFlow>('PUT', `/workflow/flows/${id}`, { body: input }),
      deleteFlow: (id: string) => request<void>('DELETE', `/workflow/flows/${id}`),
      /** Use one workflow for a leave or claim type. dryRun (the default): only say what would change. */
      assignFlow: (subject: 'LEAVE' | 'CLAIM', typeId: string, flowId: string, dryRun = true) =>
        request<FlowAssignment>('POST', `/workflow/flows/assign${query({ subject })}`, { body: { typeId, flowId, dryRun } }),
      reorderFlows: (subject: FlowSubject, order: string[]) =>
        request<ApprovalFlow[]>('PUT', `/workflow/flows/order${query({ subject })}`, { body: { order } }),
      preview: (employeeId: string, leaveTypeId: string, days: number) =>
        request<ApprovalPreview>('GET', `/leave/settings/approval-preview${query({ employeeId, leaveTypeId, days })}`),
      claimPreview: (employeeId: string, claimTypeId: string, amount: number) =>
        request<ClaimApprovalPreview>('GET', `/claims/settings/approval-preview${query({ employeeId, claimTypeId, amount })}`),
    },
    notices: {
      mine: (kind?: NoticeKind) => request<Notice[]>('GET', `/notices${query({ kind })}`),
      managed: (kind?: NoticeKind) => request<Notice[]>('GET', `/notices${query({ kind, manage: 'true' })}`),
      counts: () => request<{ unread: number; toAcknowledge: number }>('GET', '/notices/counts'),
      /** Opening one you're meant to read counts as read. */
      open: (id: string) => request<Notice>('GET', `/notices/${id}`),
      acknowledge: (id: string) => request<Notice>('POST', `/notices/${id}/acknowledge`),
      attachment: (id: string, fileId: string) => request<Blob>('GET', `/notices/${id}/attachments/${fileId}`, { blob: true }),
      create: (input: NoticeInput) => request<Notice>('POST', '/notices', { body: input }),
      update: (id: string, input: NoticeInput) => request<Notice>('PUT', `/notices/${id}`, { body: input }),
      publish: (id: string) => request<Notice>('POST', `/notices/${id}/publish`),
      archive: (id: string) => request<void>('POST', `/notices/${id}/archive`),
      remove: (id: string) => request<void>('DELETE', `/notices/${id}`),
      receipts: (id: string) => request<NoticeReceipts>('GET', `/notices/${id}/receipts`),
      audiences: () => request<{ departments: { id: string; name: string }[]; entities: { id: string; name: string }[] }>('GET', '/notices/audiences'),
    },
    letters: {
      mine: () => request<IssuedLetter[]>('GET', '/letters/mine'),
      file: (id: string) => request<Blob>('GET', `/letters/${id}/file`, { blob: true }),
      all: () => request<IssuedLetter[]>('GET', '/letters'),
      issue: (input: { templateId: string; employeeId: string; values: Record<string, string> }) =>
        request<IssuedLetter>('POST', '/letters', { body: input }),
      preview: (input: { templateId: string; employeeId: string; values: Record<string, string> }) =>
        request<Blob>('POST', '/letters/preview', { body: input, blob: true }),
      withdraw: (id: string) => request<void>('POST', `/letters/${id}/withdraw`),
      letterhead: () => request<Letterhead>('GET', '/letters/letterhead'),
      saveLetterhead: (input: LetterheadInput) => request<Letterhead>('PUT', '/letters/letterhead', { body: input }),
      templates: () => request<LetterTemplate[]>('GET', '/letters/templates'),
      autoFields: () => request<string[]>('GET', '/letters/templates/fields'),
      createTemplate: (input: LetterTemplateInput) => request<LetterTemplate>('POST', '/letters/templates', { body: input }),
      updateTemplate: (id: string, input: LetterTemplateInput) => request<LetterTemplate>('PUT', `/letters/templates/${id}`, { body: input }),
      deleteTemplate: (id: string) => request<void>('DELETE', `/letters/templates/${id}`),
    },
    grievances: {
      mine: () => request<Grievance[]>('GET', '/grievances/mine'),
      submit: (input: { category: GrievanceCategory; subject: string; body: string; fileId?: string | null; anonymous: boolean }) =>
        request<GrievanceDetail>('POST', '/grievances', { body: input }),
      /** status: ACTIVE, OPEN, IN_PROGRESS, RESOLVED, CLOSED or ALL. */
      all: (status = 'ACTIVE') => request<Grievance[]>('GET', `/grievances${query({ status })}`),
      handlers: () => request<{ employeeId: string; name: string }[]>('GET', '/grievances/handlers'),
      detail: (id: string) => request<GrievanceDetail>('GET', `/grievances/${id}`),
      reply: (id: string, body: string, fileId?: string | null) =>
        request<GrievanceDetail>('POST', `/grievances/${id}/replies`, { body: { body, fileId } }),
      setStatus: (id: string, status: GrievanceStatus, note?: string) =>
        request<GrievanceDetail>('POST', `/grievances/${id}/status`, { body: { status, note } }),
      assign: (id: string, assignee: string | null) => request<GrievanceDetail>('POST', `/grievances/${id}/assign`, { body: { assignee } }),
      close: (id: string) => request<GrievanceDetail>('POST', `/grievances/${id}/close`),
      reopen: (id: string, reason: string) => request<GrievanceDetail>('POST', `/grievances/${id}/reopen`, { body: { reason } }),
      file: (id: string, fileId: string) => request<Blob>('GET', `/grievances/${id}/files/${fileId}`, { blob: true }),
    },
    notifications: {
      list: (limit = 30) => request<{ unread: number; items: AppNotification[] }>('GET', `/notifications?limit=${limit}`),
      read: (id: string) => request<void>('POST', `/notifications/${id}/read`),
      readAll: () => request<void>('POST', '/notifications/read-all'),
      preferences: () => request<NotificationPreference[]>('GET', '/notifications/preferences'),
      savePreferences: (prefs: NotificationPreference[]) =>
        request<NotificationPreference[]>('PUT', '/notifications/preferences', { body: prefs }),
    },
    files: {
      /** PDF, JPG or PNG up to 5 MB. Returns the id to attach to a request. */
      upload: (file: File) => {
        const form = new FormData();
        form.append('file', file);
        return request<FileMeta>('POST', '/files', { body: form });
      },
    },
    employees: (q?: string) =>
      request<{ scope: DataScope; employees: EmployeeSummary[] }>('GET', `/employees${q ? `?q=${encodeURIComponent(q)}` : ''}`),
    profiles: {
      sections: () => request<ProfileSectionSpec[]>('GET', '/employees/profile-sections'),
      /** id: an employee id or "me". */
      get: (id: string) => request<EmployeeProfile>('GET', `/employees/${id}/profile`),
      /** HR only: saves the section directly. */
      saveSection: (id: string, section: string, data: SectionData) =>
        request<EmployeeProfile>('PUT', `/employees/${id}/profile/${section}`, { body: data }),
      saveJob: (id: string, job: JobDetails) => request<EmployeeProfile>('PUT', `/employees/${id}/job`, { body: job }),
      photo: (id: string) => request<Blob>('GET', `/employees/${id}/photo`, { blob: true }),
      setPhoto: (id: string, fileId: string) => request<void>('PUT', `/employees/${id}/photo`, { body: { fileId } }),
      removePhoto: (id: string) => request<void>('DELETE', `/employees/${id}/photo`),
      changes: {
        mine: () => request<ProfileChange[]>('GET', '/employees/me/changes'),
        ask: (section: string, data: SectionData, note?: string) =>
          request<ProfileChange>('POST', '/employees/me/changes', { body: { section, data, note } }),
        resubmit: (id: string, data: SectionData, note?: string) =>
          request<ProfileChange>('PUT', `/employees/changes/${id}`, { body: { data, note } }),
        cancel: (id: string) => request<void>('POST', `/employees/changes/${id}/cancel`),
        detail: (id: string) => request<ProfileChangeDetail>('GET', `/employees/changes/${id}`),
      },
      documents: (id: string) => request<EmployeeDocument[]>('GET', `/employees/${id}/documents`),
      addDocument: (id: string, input: { fileId: string; category: DocumentCategory; description?: string }) =>
        request<EmployeeDocument>('POST', `/employees/${id}/documents`, { body: input }),
      documentFile: (id: string, documentId: string) =>
        request<Blob>('GET', `/employees/${id}/documents/${documentId}/file`, { blob: true }),
      deleteDocument: (id: string, documentId: string) => request<void>('DELETE', `/employees/${id}/documents/${documentId}`),
      assets: (id: string) => request<EmployeeAsset[]>('GET', `/employees/${id}/assets`),
      addAsset: (id: string, input: AssetInput) => request<EmployeeAsset>('POST', `/employees/${id}/assets`, { body: input }),
      updateAsset: (id: string, assetId: string, input: AssetInput) =>
        request<EmployeeAsset>('PUT', `/employees/${id}/assets/${assetId}`, { body: input }),
      deleteAsset: (id: string, assetId: string) => request<void>('DELETE', `/employees/${id}/assets/${assetId}`),
    },
    payroll: {
      mine: () => request<PayrollDocument[]>('GET', '/payroll/me/documents'),
      detail: (id: string) => request<{ doc: PayrollDocument; lines: PayLine[] }>('GET', `/payroll/documents/${id}`),
      file: (id: string) => request<Blob>('GET', `/payroll/documents/${id}/file`, { blob: true }),
      /** A copy that asks for this password to open. */
      protectedCopy: (id: string, password: string) =>
        request<Blob>('POST', `/payroll/documents/${id}/protected`, { body: { password }, blob: true }),
      periods: (kind: PayrollKind, year?: number) => request<PayrollPeriodSummary[]>('GET', `/payroll/periods${query({ kind, year })}`),
      period: (kind: PayrollKind, period: string, formCode?: string | null) =>
        request<PayrollPeriodView>('GET', `/payroll/documents${query({ kind, period, formCode: formCode ?? undefined })}`),
      taxForms: () => request<Record<string, TaxFormCode>>('GET', '/payroll/tax-forms'),
      upload: (input: { kind: PayrollKind; period: string; formCode?: string | null; items: { fileId: string; employeeId?: string }[] }) =>
        request<PayrollUploadResult>('POST', '/payroll/documents/upload', { body: input }),
      /** template: a layout code; leave it out to use the company's default. */
      generate: (period: string, csv: string, template?: string) =>
        request<PayrollUploadResult>('POST', '/payroll/payslips/generate', { body: { period, csv, template } }),
      /** Draws a month's generated payslips again in a template (same figures; published ones stay published). */
      redraw: (period: string, template?: string, ids?: string[]) =>
        request<PayrollUploadResult>('POST', '/payroll/payslips/redraw', { body: { period, template, ids } }),
      templates: () => request<{ defaultTemplate: string; templates: PayslipTemplate[] }>('GET', '/payroll/templates'),
      setDefaultTemplate: (template: string) =>
        request<{ defaultTemplate: string; templates: PayslipTemplate[] }>('POST', '/payroll/templates/default', { body: { template } }),
      templateSample: (code: string) => request<Blob>('GET', `/payroll/templates/${code}/sample`, { blob: true }),
      publish: (input: { kind: PayrollKind; period: string; formCode?: string | null; ids?: string[] }) =>
        request<{ published: number }>('POST', '/payroll/documents/publish', { body: input }),
      unpublish: (id: string) => request<void>('POST', `/payroll/documents/${id}/unpublish`),
      remove: (id: string) => request<void>('DELETE', `/payroll/documents/${id}`),
      exportPreview: (month: string) => request<PayrollExportSummary>('GET', `/payroll/export${query({ month })}`),
      exportCsv: (month: string) => request<Blob>('GET', `/payroll/export/csv${query({ month })}`, { blob: true }),
      dashboard: (year: number) => request<PayrollDashboard>('GET', `/payroll/dashboard${query({ year })}`),
    },
    approvals: {
      inbox: () => request<{ count: number; items: InboxItem[] }>('GET', '/approvals/inbox'),
      sendBack: (instanceId: string, comment: string) =>
        request<void>('POST', `/approvals/${instanceId}/return`, { body: { comment } }),
      approve: (instanceId: string, comment?: string) =>
        request<void>('POST', `/approvals/${instanceId}/approve`, { body: { comment } }),
      reject: (instanceId: string, comment: string) =>
        request<void>('POST', `/approvals/${instanceId}/reject`, { body: { comment } }),
      /** Up to 100 at once; each is decided on its own. */
      approveMany: (instanceIds: string[], comment?: string) =>
        request<ApproveManyResult>('POST', '/approvals/approve', { body: { instanceIds, comment } }),
    },
    leave: {
      types: () => request<LeaveType[]>('GET', '/leave/types'),
      holidays: (year?: number) => request<Holiday[]>('GET', `/leave/holidays${year ? `?year=${year}` : ''}`),
      balances: (employeeId?: string) =>
        request<LeaveBalance[]>('GET', `/leave/balances${employeeId ? `?employeeId=${employeeId}` : ''}`),
      preview: (start: string, end: string, dayPart: DayPart) =>
        request<{ days: number; holidays: Holiday[]; workDays: number[] }>('GET', `/leave/preview?start=${start}&end=${end}&dayPart=${dayPart}`),
      resubmit: (id: string, input: LeaveApplyInput) => request<LeaveRequest>('PUT', `/leave/requests/${id}`, { body: input }),
      recordFor: (input: LeaveApplyInput & { employeeId: string; approveNow: boolean }) =>
        request<LeaveRequest>('POST', '/leave/requests/on-behalf', { body: input }),
      cancelByHr: (id: string, reason: string) => request<void>('POST', `/leave/requests/${id}/hr-cancel`, { body: { reason } }),
      attachment: (id: string) => request<Blob>('GET', `/leave/requests/${id}/attachment`, { blob: true }),
      report: (q: { year?: number; departmentId?: string; leaveTypeId?: string } = {}) =>
        request<LeaveReport>('GET', `/leave/reports${query(q)}`),
      reportCsv: (q: { year?: number; departmentId?: string; leaveTypeId?: string } = {}) =>
        request<Blob>('GET', `/leave/reports/csv${query(q)}`, { blob: true }),
      mine: (year?: number) => request<LeaveRequest[]>('GET', `/leave/requests/mine${year ? `?year=${year}` : ''}`),
      drafts: {
        list: () => request<LeaveRequest[]>('GET', '/leave/drafts'),
        create: (input: LeaveApplyInput) => request<LeaveRequest>('POST', '/leave/drafts', { body: input }),
        update: (id: string, input: LeaveApplyInput) => request<LeaveRequest>('PUT', `/leave/drafts/${id}`, { body: input }),
        remove: (id: string) => request<void>('DELETE', `/leave/drafts/${id}`),
        submit: (id: string, attachmentId?: string | null) =>
          request<LeaveRequest>('POST', `/leave/drafts/${id}/submit`, { body: { attachmentId: attachmentId ?? null } }),
      },
      apply: (input: LeaveApplyInput) => request<LeaveRequest>('POST', '/leave/requests', { body: input }),
      cancel: (id: string) => request<void>('POST', `/leave/requests/${id}/cancel`),
      detail: (id: string) => request<{ request: LeaveRequest; timeline: TimelineStep[] }>('GET', `/leave/requests/${id}`),
      calendar: (from: string, to: string, includePending = false) =>
        request<{ scope: DataScope; requests: LeaveRequest[] }>(
          'GET', `/leave/calendar?from=${from}&to=${to}&includePending=${includePending}`),
      settings: {
        types: () => request<LeaveType[]>('GET', '/leave/settings/types'),
        createType: (input: LeaveTypeInput) => request<LeaveType>('POST', '/leave/settings/types', { body: input }),
        updateType: (id: string, input: LeaveTypeInput) => request<LeaveType>('PUT', `/leave/settings/types/${id}`, { body: input }),
        holidays: () => request<Holiday[]>('GET', '/leave/settings/holidays'),
        addHoliday: (date: string, name: string) => request<Holiday>('POST', '/leave/settings/holidays', { body: { date, name } }),
        deleteHoliday: (id: string) => request<void>('DELETE', `/leave/settings/holidays/${id}`),
        entitlements: (employeeId: string, year: number) =>
          request<LeaveBalance[]>('GET', `/leave/settings/entitlements/${employeeId}?year=${year}`),
        setEntitlement: (employeeId: string, typeId: string, year: number, input: { entitled: number | null; carried?: number | null; note?: string }) =>
          request<LeaveBalance[]>('PUT', `/leave/settings/entitlements/${employeeId}/${typeId}?year=${year}`, { body: input }),
        resetEntitlement: (employeeId: string, typeId: string, year: number) =>
          request<LeaveBalance[]>('DELETE', `/leave/settings/entitlements/${employeeId}/${typeId}?year=${year}`),
        yearEndRuns: () => request<{ year: number; runAt: string; people: number; days: number }[]>('GET', '/leave/settings/year-end'),
        runYearEnd: (year: number) => request<YearEndResult>('POST', `/leave/settings/year-end/${year}`),
        workWeeks: () => request<{ entityId: string; name: string; workDays: number[] }[]>('GET', '/leave/settings/work-week'),
        setWorkWeek: (entityId: string, workDays: number[]) =>
          request<{ entityId: string; name: string; workDays: number[] }[]>('PUT', `/leave/settings/work-week/${entityId}`, { body: { workDays } }),
      },
    },
    /** Everything the signed-in person may see that matches (at least 2 characters), a few of each kind. */
    search: (q: string) => request<{ query: string; hits: SearchHit[] }>('GET', `/search${query({ q })}`),
    claims: {
      types: () => request<ClaimType[]>('GET', '/claims/types'),
      mine: (year?: number) => request<Claim[]>('GET', `/claims/mine${query({ year })}`),
      usage: (month?: string) =>
        request<{ currency: string; month: string; types: ClaimUsage[] }>('GET', `/claims/usage${query({ month })}`),
      submit: (input: ClaimInput) => request<Claim>('POST', '/claims', { body: input }),
      resubmit: (id: string, input: ClaimInput) => request<Claim>('PUT', `/claims/${id}`, { body: input }),
      cancel: (id: string) => request<void>('POST', `/claims/${id}/cancel`),
      detail: (id: string) => request<{ claim: Claim; timeline: TimelineStep[] }>('GET', `/claims/${id}`),
      receipt: (id: string) => request<Blob>('GET', `/claims/${id}/receipt`, { blob: true }),
      team: (q: { status?: ClaimFilter; from?: string; to?: string } = {}) =>
        request<{ scope: DataScope; claims: Claim[] }>('GET', `/claims/team${query(q)}`),
      pay: (claimIds: string[], reference?: string) =>
        request<{ paid: number }>('POST', '/claims/pay', { body: { claimIds, reference } }),
      exportCsv: (month: string) => request<Blob>('GET', `/claims/export${query({ month })}`, { blob: true }),
      report: (q: { year?: number; month?: number; claimTypeId?: string } = {}) =>
        request<ClaimsReport>('GET', `/claims/reports${query(q)}`),
      reportCsv: (q: { year?: number; month?: number; claimTypeId?: string } = {}) =>
        request<Blob>('GET', `/claims/reports/csv${query(q)}`, { blob: true }),
      settings: {
        types: () => request<ClaimType[]>('GET', '/claims/settings/types'),
        createType: (input: ClaimTypeInput) => request<ClaimType>('POST', '/claims/settings/types', { body: input }),
        updateType: (id: string, input: ClaimTypeInput) => request<ClaimType>('PUT', `/claims/settings/types/${id}`, { body: input }),
      },
    },
    sites: {
      list: () => request<SiteSummary[]>('GET', '/attendance/sites'),
      entities: () => request<{ id: string; name: string }[]>('GET', '/attendance/sites/entities'),
      get: (id: string) => request<SiteDetail>('GET', `/attendance/sites/${id}`),
      create: (input: SiteInput) => request<SiteDetail>('POST', '/attendance/sites', { body: input }),
      update: (id: string, input: SiteInput) => request<SiteDetail>('PUT', `/attendance/sites/${id}`, { body: input }),
      remove: (id: string) => request<void>('DELETE', `/attendance/sites/${id}`),
      addMembers: (id: string, employeeIds: string[]) =>
        request<SiteMember[]>('POST', `/attendance/sites/${id}/members`, { body: { employeeIds } }),
      removeMembers: (id: string, employeeIds: string[]) =>
        request<SiteMember[]>('POST', `/attendance/sites/${id}/members/remove`, { body: { employeeIds } }),
      /** Would this person be able to clock in with this location and Wi-Fi? Uses their real sites; saves nothing. */
      check: (input: { employeeId: string; lat?: number | null; lng?: number | null; accuracyM?: number | null; wifiBssid?: string | null;
        selfie?: boolean; remarks?: string | null; mockLocation?: boolean }) =>
        request<ClockCheck>('POST', '/attendance/sites/check', { body: input }),
      /** Your own active sites (for the clock-in screen). */
      mine: () => request<MySite[]>('GET', '/punches/sites'),
    },
    attendance: {
      /** Who's in, late, not in yet, on leave or off; date (YYYY-MM-DD) looks back at an earlier day. */
      today: (date?: string) => request<Today>('GET', `/attendance/today${date ? `?date=${date}` : ''}`),
      /** Totals per person in your attendance:timesheets scope. from/to: YYYY-MM-DD, up to 62 days; default this month. */
      timesheets: (range: { from?: string; to?: string } = {}) => request<TeamTimesheets>('GET', `/attendance/timesheets${query(range)}`),
      report: (q: { year?: number; month?: number } = {}) => request<AttendanceReport>('GET', `/attendance/reports${query(q)}`),
      reportCsv: (q: { year?: number; month?: number } = {}) => request<Blob>('GET', `/attendance/reports/csv${query(q)}`, { blob: true }),
      shiftTemplates: {
        list: () => request<ShiftTemplate[]>('GET', '/attendance/shift-templates'),
        create: (input: ShiftTemplateInput) => request<ShiftTemplate>('POST', '/attendance/shift-templates', { body: input }),
        update: (id: string, input: ShiftTemplateInput) => request<ShiftTemplate>('PUT', `/attendance/shift-templates/${id}`, { body: input }),
        remove: (id: string) => request<void>('DELETE', `/attendance/shift-templates/${id}`),
      },
      rosters: {
        sites: () => request<{ id: string; name: string }[]>('GET', '/attendance/rosters/sites'),
        /** week: any day of it (YYYY-MM-DD). */
        week: (siteId: string, week: string) => request<RosterWeek>('GET', `/attendance/rosters${query({ siteId, week })}`),
        addShift: (input: RosterShiftInput) => request<RosterShift>('POST', '/attendance/rosters/shifts', { body: input }),
        updateShift: (id: string, input: RosterShiftInput) => request<RosterShift>('PUT', `/attendance/rosters/shifts/${id}`, { body: input }),
        removeShift: (id: string) => request<void>('DELETE', `/attendance/rosters/shifts/${id}`),
        copyPrevious: (siteId: string, week: string) =>
          request<{ copied: number; skipped: number }>('POST', '/attendance/rosters/copy-previous', { body: { siteId, week } }),
        clear: (siteId: string, week: string) => request<number>('POST', '/attendance/rosters/clear', { body: { siteId, week } }),
        /** Fails with ROSTER_HAS_ERRORS, or ROSTER_HAS_WARNINGS until acknowledge is true (problems in the error). */
        publish: (siteId: string, week: string, acknowledge = false) =>
          request<RosterWeek>('POST', '/attendance/rosters/publish', { body: { siteId, week, acknowledge } }),
        unpublish: (siteId: string, week: string) => request<RosterWeek>('POST', '/attendance/rosters/unpublish', { body: { siteId, week } }),
      },
      /** The published roster of a site you're at (siteId: default your first site; week: any day of it). */
      siteRoster: (week: string, siteId?: string) => request<SiteRoster>('GET', `/attendance/site-rosters${query({ siteId, week })}`),
      /** Your published shifts (up to 62 days). */
      mySchedule: (from: string, to: string) => request<MyShift[]>('GET', `/attendance/schedule/me${query({ from, to })}`),
      availability: {
        mine: () => request<AvailabilityDay[]>('GET', '/attendance/availability/me'),
        save: (days: AvailabilityDay[]) => request<AvailabilityDay[]>('PUT', '/attendance/availability/me', { body: { days } }),
      },
      /** Open shifts at your sites for the next four weeks. */
      openShifts: () => request<{ shift: PublishedShift; requested: boolean }[]>('GET', '/attendance/open-shifts'),
      shiftRequests: {
        mine: () => request<ShiftRequest[]>('GET', '/attendance/shift-requests'),
        /** Colleagues at the shift's site, with their shifts that week (to swap for). */
        options: (shiftId: string) =>
          request<{ shift: PublishedShift; colleagues: { id: string; fullName: string; shifts: PublishedShift[] }[] }>('GET', `/attendance/shift-requests/options${query({ shiftId })}`),
        ask: (input: { kind: ShiftRequestKind; shiftId: string; colleagueId?: string | null; colleagueShiftId?: string | null; reason?: string | null }) =>
          request<ShiftRequest>('POST', '/attendance/shift-requests', { body: input }),
        accept: (id: string) => request<ShiftRequest>('POST', `/attendance/shift-requests/${id}/accept`),
        decline: (id: string) => request<void>('POST', `/attendance/shift-requests/${id}/decline`),
        cancel: (id: string) => request<void>('POST', `/attendance/shift-requests/${id}/cancel`),
        detail: (id: string) => request<{ request: ShiftRequest; timeline: TimelineStep[] }>('GET', `/attendance/shift-requests/${id}`),
      },
      locks: {
        /** month: YYYY-MM */
        status: (month: string) => request<PeriodLockStatus>('GET', `/attendance/locks${query({ month })}`),
        /** The locked months of a year (YYYY-MM). */
        year: (year: number) => request<string[]>('GET', `/attendance/locks/year${query({ year })}`),
        lock: (month: string) => request<PeriodLockStatus>('POST', '/attendance/locks', { body: { month } }),
        unlock: (month: string, reason: string) => request<PeriodLockStatus>('POST', '/attendance/locks/unlock', { body: { month, reason } }),
      },
      /** One person's days ('me' for your own). */
      timesheet: (employeeId: string | 'me', range: { from?: string; to?: string } = {}) =>
        request<Timesheet>('GET', `/attendance/timesheets/${employeeId}${query(range)}`),
      corrections: {
        /** Your own requests, newest day first. */
        mine: () => request<AttendanceCorrection[]>('GET', '/attendance/corrections'),
        submit: (input: CorrectionInput) => request<AttendanceCorrection>('POST', '/attendance/corrections', { body: input }),
        /** Edit one sent back for changes; it goes for approval again. */
        resubmit: (id: string, input: CorrectionInput) => request<AttendanceCorrection>('PUT', `/attendance/corrections/${id}`, { body: input }),
        cancel: (id: string) => request<void>('POST', `/attendance/corrections/${id}/cancel`),
        detail: (id: string) => request<CorrectionDetail>('GET', `/attendance/corrections/${id}`),
      },
      overtime: {
        /** Your own requests, newest day first. */
        mine: () => request<OvertimeRequest[]>('GET', '/attendance/overtime'),
        submit: (input: OvertimeInput) => request<OvertimeRequest>('POST', '/attendance/overtime', { body: input }),
        /** Edit one sent back for changes; it goes for approval again. */
        resubmit: (id: string, input: OvertimeInput) => request<OvertimeRequest>('PUT', `/attendance/overtime/${id}`, { body: input }),
        cancel: (id: string) => request<void>('POST', `/attendance/overtime/${id}/cancel`),
        detail: (id: string) => request<OvertimeDetail>('GET', `/attendance/overtime/${id}`),
        /** The overtime rule that applies to you, and your banked hours. */
        myRule: () => request<{ rule: OvertimeRule; bankHours: number }>('GET', '/attendance/overtime/rule'),
        rules: {
          list: () => request<OvertimeRuleSettings>('GET', '/attendance/overtime-rules'),
          create: (input: OvertimeRuleInput) => request<OvertimeRule>('POST', '/attendance/overtime-rules', { body: input }),
          update: (id: string, input: OvertimeRuleInput) => request<OvertimeRule>('PUT', `/attendance/overtime-rules/${id}`, { body: input }),
          remove: (id: string) => request<void>('DELETE', `/attendance/overtime-rules/${id}`),
        },
      },
    },
    admin: {
      catalog: () => request<CatalogModule[]>('GET', '/admin/catalog'),
      users: (q?: string) => request<AdminUser[]>('GET', `/admin/users${q ? `?q=${encodeURIComponent(q)}` : ''}`),
      createUser: (input: AdminUserInput) =>
        request<{ user: AdminUser; temporaryPassword: string }>('POST', '/admin/users', { body: input }),
      updateUser: (id: string, input: AdminUserInput) => request<AdminUser>('PUT', `/admin/users/${id}`, { body: input }),
      resetPassword: (id: string) =>
        request<{ temporaryPassword: string }>('POST', `/admin/users/${id}/reset-password`),
      roles: () => request<AdminRole[]>('GET', '/admin/roles'),
      createRole: (input: AdminRoleInput) => request<AdminRole>('POST', '/admin/roles', { body: input }),
      updateRole: (id: string, input: AdminRoleInput) => request<AdminRole>('PUT', `/admin/roles/${id}`, { body: input }),
      deleteRole: (id: string) => request<void>('DELETE', `/admin/roles/${id}`),
      company: () => request<CompanySettings>('GET', '/admin/company'),
      updateCompany: (input: { name: string; code: string }) =>
        request<CompanySettings>('PUT', '/admin/company', { body: input }),
      setModule: (key: string, enabled: boolean) =>
        request<CompanySettings>('PUT', `/admin/company/modules/${key}`, { body: { enabled } }),
      departments: () => request<Department[]>('GET', '/admin/departments'),
      addDepartment: (name: string) => request<Department>('POST', '/admin/departments', { body: { name } }),
      renameDepartment: (id: string, name: string) => request<Department>('PUT', `/admin/departments/${id}`, { body: { name } }),
      deleteDepartment: (id: string) => request<void>('DELETE', `/admin/departments/${id}`),
      departmentMembers: (id: string) => request<DepartmentMember[]>('GET', `/admin/departments/${id}/members`),
      mail: {
        get: () => request<MailSettingsView>('GET', '/admin/company/mail'),
        save: (input: MailSettingsInput) => request<MailSettingsView>('PUT', '/admin/company/mail', { body: input }),
        remove: () => request<void>('DELETE', '/admin/company/mail'),
        /** Sends a test through the settings in the form (empty password = the saved one). */
        test: (settings: MailSettingsInput, to?: string) =>
          request<{ ok: boolean; message: string; detail: string | null }>('POST', '/admin/company/mail/test', { body: { settings, to } }),
      },
    },
  };
}

export type ApiClient = ReturnType<typeof createApiClient>;
