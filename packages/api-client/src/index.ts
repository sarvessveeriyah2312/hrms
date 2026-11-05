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
export type FlowSubject = 'LEAVE' | 'CLAIM';

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
      reorderFlows: (subject: FlowSubject, order: string[]) =>
        request<ApprovalFlow[]>('PUT', `/workflow/flows/order${query({ subject })}`, { body: { order } }),
      preview: (employeeId: string, leaveTypeId: string, days: number) =>
        request<ApprovalPreview>('GET', `/leave/settings/approval-preview${query({ employeeId, leaveTypeId, days })}`),
      claimPreview: (employeeId: string, claimTypeId: string, amount: number) =>
        request<ClaimApprovalPreview>('GET', `/claims/settings/approval-preview${query({ employeeId, claimTypeId, amount })}`),
    },
    notifications: {
      list: (limit = 30) => request<{ unread: number; items: AppNotification[] }>('GET', `/notifications?limit=${limit}`),
      read: (id: string) => request<void>('POST', `/notifications/${id}/read`),
      readAll: () => request<void>('POST', '/notifications/read-all'),
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
    approvals: {
      inbox: () => request<{ count: number; items: InboxItem[] }>('GET', '/approvals/inbox'),
      sendBack: (instanceId: string, comment: string) =>
        request<void>('POST', `/approvals/${instanceId}/return`, { body: { comment } }),
      approve: (instanceId: string, comment?: string) =>
        request<void>('POST', `/approvals/${instanceId}/approve`, { body: { comment } }),
      reject: (instanceId: string, comment: string) =>
        request<void>('POST', `/approvals/${instanceId}/reject`, { body: { comment } }),
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
