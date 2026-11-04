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
- ✅ Approval workflow settings (Administration → Approval workflows, `workflow:manage`, Super admin only by default): named workflows with conditions (leave types, departments, day range) and ordered steps (manager level 1–3, a specific person, anyone with a role, HR). First switched-on match wins; otherwise the leave type's own chain. Unfillable steps are skipped with a reason; "Try it" previews who would approve.
- 📋 Deferred items are tracked in [BACKLOG.md](BACKLOG.md).
- ⏳ Next: identity (2FA, SSO, devices, login rate limits, a persistent signing key), org config (sites, shifts), leave, claims, the workflow engine, notifications and offline clock-in queue. See the PRD for the full backlog.
