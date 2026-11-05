# HRMS Platform

Multi-tenant HRMS: attendance, leave, claims, approvals and HR records on web and mobile.
Requirements live in the BRD and PRD docs; screens in the UI/UX design canvas.

| Part | Stack | Folder |
| --- | --- | --- |
| API | Java 21, Spring Boot 4.1, Spring Security (JWT resource server), Spring Data JPA, Flyway, Spring Modulith, PostgreSQL 16, Redis, Firebase Admin (FCM) | `backend/` |
| Web | React 19, TypeScript, Vite 8, React Router, TanStack Query, React Hook Form + Zod, i18next | `apps/web/` |
| Mobile | React Native 0.88 with Expo SDK 58, Expo Router (native tabs), expo-location, expo-secure-store | `apps/mobile/` |
| Shared | Design tokens, typed API client | `packages/ui-tokens/`, `packages/api-client/` |
| Local services | PostgreSQL, Redis, MinIO (S3-compatible) | `infra/docker-compose.yml` |

## Getting started

```bash
# 1. Local services (needs Docker Desktop or OrbStack)
npm run infra:up
#    ...or use Homebrew Postgres + Redis instead of Docker (one-time setup):
#    psql -d postgres -c "create role hrms_app login password 'hrms_app' nobypassrls" -c "create database hrms owner hrms_app"

# 2. JavaScript dependencies (npm workspaces)
npm install

# 3. API on http://localhost:8080 (Swagger UI at /swagger-ui)
npm run dev:api

# 4. Web on http://localhost:5173
npm run dev:web

# 5. Mobile (Expo dev server; press i / a for simulators)
npm run dev:mobile
```

Copy `.env.example` to `.env` (or set the variables in your IDE) and adjust as needed.

### Signing in locally

`npm run dev:api` runs the `local` profile, which creates two demo companies on first start:

| Login | Company (code) | Role |
| --- | --- | --- |
| `aisyah@demo.hrms.local` | Demo Sdn Bhd (`DEMO`) and Demo Pte Ltd (`DEMO-SG`) | Super admin / Manager: shows the company picker |
| `priya@demo.hrms.local` | Demo Sdn Bhd | HR admin |
| `daniel@demo.hrms.local` | Demo Sdn Bhd | Manager of Wei Ling and Arjun |
| `weiling@demo.hrms.local`, `arjun@demo.hrms.local` | Demo Sdn Bhd | Employee |

The password is `hrms-demo-123` (override with `HRMS_SEED_PASSWORD`). The web dev server proxies `/api` to `:8080`, so sign in at http://localhost:5173/login.

How sign-in works: `POST /api/v1/auth/login` returns a 15-minute RS256 access token (kept in memory by the web app) and sets an HttpOnly, SameSite=Strict refresh cookie. `POST /api/v1/auth/refresh` rotates it; reusing an old refresh token revokes all of that user's sessions. The signing key is generated at startup, so restarting the API signs everyone in again through the refresh cookie.

## Backend layout (modular monolith)

`com.hrms.<module>`, one package per module; `ModularityTests` fails the build when a module reaches into another module's internals.

| Module | Owns |
| --- | --- |
| `platform` | Tenants, entities, module toggles, audit log |
| `identity` | Users, SSO, 2FA, devices, tokens, roles and permissions |
| `org` | Departments, sites (geofence, Wi-Fi), shifts, holidays |
| `employee` | Employee records, change requests, documents |
| `attendance` | Punches, clock-in rule engine, corrections, overtime (**sample slice implemented**) |
| `leave`, `claims` | Leave and claims |
| `workflow` | Approval chains and steps for every request type |
| `notification` | Push (Firebase FCM or log fallback), email, in-app |
| `reporting`, `files` | Exports; pre-signed uploads |
| `shared` | Tenancy, security, error handling |

### Security rules baked into the scaffold

- **Tenant from the token only.** `TenantContextFilter` reads `tenant_id` from the JWT, and `TenantAwareDataSource` sets `app.tenant_id` on every connection so **PostgreSQL row-level security** enforces isolation as a second line of defence.
- **Permissions on the server.** Endpoints use `@PreAuthorize("@perm.has('module:action')")`. The acting employee always comes from the token, never the request body.
- **No secrets in clients.** The web keeps the access token in memory (refresh token as an HttpOnly cookie); mobile uses SecureStore. Firebase credentials come from `GOOGLE_APPLICATION_CREDENTIALS` and are git-ignored.
- **No background location.** Mobile blocks `ACCESS_BACKGROUND_LOCATION`; location is read only at clock-in.
- **Errors** are RFC 9457 Problem Details with a stable `code`.

## Firebase

Firebase is supported, never the source of truth:

- **Push:** set `FIREBASE_ENABLED=true`, `FIREBASE_PROJECT_ID` and `GOOGLE_APPLICATION_CREDENTIALS`. Without them, `PushSender` logs instead of sending.
- **Optional later:** Firebase Auth as an extra identity provider, and Firestore as a read-only live dashboard feed (see the PRD).

## Tests

```bash
npm run test:api            # backend (context tests need Docker for Testcontainers)
npm run typecheck           # web, mobile and packages
```

## What's scaffolded vs. to build

- ✅ Project structure, build tooling, security config, tenancy + RLS, error format, Flyway baseline schema, clock-in rule engine with tests, Firebase push adapter, web shell (sidebar, dashboard, approvals, login), mobile tabs (Home, Attendance clock-in, Requests, Inbox, Me), shared tokens and API client.
- ✅ Identity: password login (email or mobile), access + refresh tokens, logout, `/me`, local demo data.
- ✅ Access control: company code and company picker at login; module licences per company; permissions as `module:action` with a data scope (Self, Team, Department, Entity, Company) enforced in SQL; built-in roles (Super admin, HR admin, Manager, Employee) plus custom roles; admin API and web pages for users (with manager and backup approver), roles and company settings. Admins can't grant more than they hold, and every admin change goes to `audit_event`.
- ✅ Approval workflow (any request type): chain from the org chart (manager, optionally their manager, optionally HR; HR when no manager), backup approver while the approver is on leave or the step is overdue, company-wide approvers can step in, no self-approval, reasons required to reject, full timeline.
- ✅ Leave: types with yearly days and their own approval chain, balances (entitled + carried − used − pending), working-day count without weekends and public holidays, half days, overlap and balance checks, cancel or withdraw, who's off (by data scope), and HR settings for types and holidays.
- ✅ Leave entitlements: per-person entitled and carried days set by HR (with a reason), automatic rules per leave type (service tiers counted on 1 January, pro-rating in the joining year, carry-over cap), and a re-runnable year-end carry-over that never overwrites hand-typed figures.
- ✅ Leave extras: carried days that expire after a set month, approvers can send a request back for changes (edit and resubmit starts a fresh chain), HR records leave for someone (approved already or through approvals) and cancels approved leave with a reason, supporting documents (PDF/JPG/PNG up to 5 MB, checked by content; only people who can see the request can open them), a working week per legal entity, and a leave report (by scope, with CSV export) on the Reports page.
- ✅ Leave calendar and planner: the Leave page has Overview, Calendar and Planner tabs. The calendar shows a month of your leave (approved, waiting, planned), your team's leave within your `leave:view` scope, public holidays and non-working days. The planner saves leave as private drafts (status `DRAFT`): not sent for approval and not taken off the balance, but shown as "planned" with a projected balance. Submitting a plan runs every normal apply check and starts the matching approval workflow.
- ✅ In-app notifications: a bell with an unread count. Approvers hear when a request reaches their step (HR steps go to every company-wide approver); requesters hear when theirs is approved, rejected or sent back (with the comment); employees hear when HR records or cancels their leave. Opening one marks it read and goes straight to the request. Written in the same transaction as the action.
- ✅ Approval workflow settings (Administration → Approval workflows, `workflow:manage`, Super admin only by default): named workflows with conditions (leave types, departments, day range) and ordered steps (manager level 1–3, a specific person, anyone with a role, HR). First switched-on match wins; otherwise the leave type's own chain. Unfillable steps are skipped with a reason; "Try it" previews who would approve. A Leave / Claims switch edits each set separately: claim workflows match on claim type, department and amount (in the claimant's currency) and fall back to the claim type's own chain.
- ✅ Claims: claim types set by HR (Claims → Claim settings, `claims:manage`) that work from a receipt (amount typed in, receipt required or optional) or from mileage (km × rate per km), with a limit per claim, a limit per person per month (waiting + approved, by expense date) and their own approval chain (manager levels, then HR). Employees submit one expense per claim (up to 90 days back, not in the future), see this month's limits, withdraw, and edit and resubmit a claim sent back. Claims go through the same approval workflow and inbox as leave (Approval workflows → Claims can route them by type, department and amount). Managers see their team's claims by `claims:view` scope; HR marks approved claims paid (with a reference, the employee is notified) and exports a month's approved claims as CSV for payroll.
- ✅ Claims report (Reports → Claims, `reports:claims` scope; the leave report uses `reports:leave`): approved and waiting claims by expense date for a year or month, by person, claim type and month, with paid and still-to-pay amounts per currency, shareable filters and CSV export.
- ✅ Search everything (⌘K / Ctrl+K or the top-bar box): pages and "New" actions, plus people, leave requests, claims, users, roles, departments, leave and claim types, holidays and approval workflows from `GET /api/v1/search`. Each kind is limited by the same permission and data scope as its page; results open the record directly (`?open=`, `?request=`, `?claim=`, `?type=` links).
- ✅ Passwords: "Forgot password?" emails a one-time link (30 minutes, SHA-256-hashed token, same answer whether or not the account exists, 3 requests an hour); the reset page shows which account it's for and signs out every device. Change password from the account menu (keeps this browser signed in, signs out the others). After an admin sets a temporary password, the person must choose their own before using the app. Rules: 10–200 characters, not a common password, not built from their email. Confirmation email after every change.
- ✉️ Email: each company can set its own SMTP server in **Company → Email** (`admin:company`): presets for Microsoft 365, Google Workspace and Amazon SES, STARTTLS / SSL / none, a test email using the form's values, and the last test result. The password is encrypted with AES-256-GCM (`HRMS_SECRET_KEY`, 32 bytes base64; required outside the local profile) and never returned. Only mail ports are allowed and hosts on private networks are refused (local profile allows them for Mailpit on port 1025). Without a company server, mail goes through the platform server (`MAIL_HOST` / `MAIL_PORT` / `MAIL_USERNAME` / `MAIL_PASSWORD`), and without that it's written to the API log. Mail is sent only after the request's transaction commits.
- ✅ Attendance sites (**Administration → Sites**, `attendance:manage`): each site has a geofence placed on an OpenStreetMap map (address search, "use my location", drag the pin, 25–1000 m radius), office Wi-Fi access points (BSSIDs in any common format, with network name and a note), the clock-in methods it accepts (geofence, office Wi-Fi, shared kiosk, plus selfie or face match; with both geofence and Wi-Fi, either or both required), an optional remark on every clock-in, and its people (add by search or whole department; someone can belong to several sites). Sites people have clocked in at can be switched off but not deleted. Clock-ins (`POST /api/v1/punches`) enforce them, in order:
  - Refused: no site assigned ("ask HR"); a fake location reported by the phone; kiosk clock-ins (until kiosk devices exist).
  - Location: refused unless one of the person's active sites accepts them (geofence and/or Wi-Fi, either or both); the reason names the site, distance and radius.
  - The matched site's extra requirements: refused without a selfie (the person's own image, never reused) or a required remark.
  - Flagged for HR rather than refused: face sites (no face matching yet), rooted phone, developer mode, phone clock off by over 2 minutes, GPS accuracy worse than 150 m.

  Refused clock-ins aren't saved; times are the server's. `GET /api/v1/punches/sites` gives the phone its sites to check before sending, and **Sites → Test a clock-in** shows what would happen for any person and location without saving.
- 🗺️ What to build next, phase by phase: [ROADMAP.md](ROADMAP.md).
- 📋 Deferred items are tracked in [BACKLOG.md](BACKLOG.md).
- ✅ **Attendance → Today** (`attendance:view`, within its data scope): everyone's day by site: in, late (first clock-in after the site's start time plus grace), not in yet (red once overdue), on approved leave (half days expect people at midday) or off (rest day or public holiday); first clock-in, last clock-out, still in, flagged clock-ins. Filter by site, status or name (kept in the link), look back at an earlier day; refreshes every minute. Each site now has working hours and grace minutes (**Sites → Working hours**) until shifts arrive. `GET /api/v1/attendance/today?date=`.
- ⏳ Next: attendance (shifts, timesheets, corrections and overtime), identity (2FA, SSO, devices, login rate limits, a persistent signing key), the mobile app screens and offline clock-in queue, payroll export and payslips, and email/push notifications. See the PRD for the full backlog.
