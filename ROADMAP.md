# Roadmap: what to build

The task list for closing the gap with INFOTECH HRMS (see `INFOTECH_HRMS_Reverse_Engineering.md`, compared on 5 Nov 2026).
Work top to bottom: each phase builds on the ones before it. Smaller follow-ups to finished modules stay in [BACKLOG.md](BACKLOG.md).

**Size:** S = a day or less · M = a few days · L = a week or more.
**Done means:** API with permission and data-scope checks, web screens in English, Bahasa Melayu and Chinese, notifications where someone needs to act, and a README line.

---

## Phase 0 · Housekeeping

- [x] **Commit the current work** on a branch (claims, workflows for claims, search, reports, table, filters, translations; migrations V10–V12). · S
- [x] **Forgot password** by email link (one-time, expiring token; same answer whether or not the account exists). · S
- [x] **Change password** from the account menu (needs the current password; signs out other sessions). · S
- [x] **Email delivery** set up locally (Mailpit) and behind an interface, so password reset and later notifications can send mail. · S
- [x] **Paging on long lists:** Users, Claims → Team and To pay, Approvals (shared `Table` / `Pager`). · S

## Phase 1 · Attendance, round 1

- [x] **Sites:** name, address, geofence (centre + radius on a map), office Wi-Fi networks, allowed clock-in methods per site; people assigned to a site. Admin screen. · M
- [x] **Clock-in rules on the server:** use the site's geofence and Wi-Fi; server time, not the phone's; reject mock locations the phone reports; remarks required if the site says so. · M
- [x] **Today (web, managers):** who's in, late, not in yet, on leave or off, by site, within the attendance:view scope; merged with approved leave. · M
- [x] **Timesheets:** each person's clock-ins by day, worked hours, late arrival, early leave and missed clock-outs; for yourself and your team. · M
- [x] **Attendance corrections** ("I forgot to clock out"): request with reason → approval workflows (new subject type) → timesheet updated. · M
- [x] **Overtime requests** with rates (1.0×, 1.5×, 2.0×, 3.0×) and hours, through approval workflows; approvers can approve several at once. · M
- [x] **Attendance report** + "See attendance reports" permission (lateness, absence, hours, overtime by person and month), with filters and CSV. · M
- [x] **Lock a period** before payroll: HR locks a month so timesheets, corrections and overtime can't change; unlock with a reason. · S

## Phase 2 · Employee profile

- [x] **Profile sections:** personal, contact, address, bank, emergency contacts, spouse, children (tax relief), education, national service (SG); profile photo. · L
- [x] **Change requests:** employees edit their profile; changes wait for HR approval, shown side by side (current vs new); approve or reject with a comment. · M
- [x] **Employee documents:** upload (5 MB, PDF/JPG/PNG), describe, view by scope; reuse the file store. · S
- [x] **Assets** assigned to an employee (laptop, phone, card) with issue and return dates. · S
- [x] **Job details for HR:** join date, position, employment type, confirmation, resignation and last day. · M

## Phase 3 · Scheduling

- [x] **Shift templates:** start, end, breaks, grace time, colour. · S
- [x] **Rosters:** plan a week by site and person (drag shifts onto people), copy last week, up to 3 shifts a day. · L
- [x] **Publish:** check for problems first (overlaps, people on leave, holidays, rest days, short rest; missing roles waits for skills), publish to staff, unpublish; notify people of changes. · M
- [x] **My schedule** for employees (web, later mobile). · S
- [x] **Availability:** employees set when they can work (up to 3 windows a day, weekly pattern). · M
- [x] **Shift swaps** with a colleague and **open shifts** to grab, both through approval. · M
- [x] **Clock-in against the shift:** lateness and overtime worked out from the scheduled shift; overtime approved before it's worked (pre-OT). · M

## Phase 4 · Payroll documents

- [x] **Payslips:** HR uploads or generates per pay period; employees view and download; read receipts for HR. · M
- [x] **Secure payslips:** password-protected PDF when sharing. Device unlock to open moves to the mobile app lock (phase 8). · S
- [x] **Tax forms by country:** IR8A (SG), EA (MY), IR56B (HK), Form 16 (India); by year, with read receipts. India's investment declarations are in BACKLOG. · L
- [x] **Payroll export:** attendance, overtime and approved claims per pay period as CSV (extends the claims export). · M
- [x] **Payroll dashboard:** head count, joiners vs leavers, cost by month (for payroll roles). · M

## Phase 5 · Communication and requests

- [x] **Announcements and HR memos** with attachments and read receipts. · M
- [x] **Personal letters** on company letterhead (offer, confirmation, increment). · M
- [x] **Company policies** with "read and acknowledged" and a list of who hasn't. · S
- [x] **Grievances / feedback:** submit with attachments, routed to a grievance manager, replies, close and reopen, admin view. · M
- [x] **Email and push notifications** for the same events as in-app, with per-person on/off per event (moves the BACKLOG item here). · M
- [x] **Overdue reminders** for approvers and alerts to backup approvers (scheduled job). · S

## Phase 6 · Leave and claims, round 2

- [ ] **Leave:** hourly leave (15-minute steps), time off in lieu for things other than overtime (overtime is done), partial cancellation of some dates, "cancel past leave" setting, approver may allow over quota, up to 3 attachments, extra people to notify. · M
- [ ] **Flexi-work (work from home) requests** with a monthly quota and approval; a flexi day relaxes the geofence. · M
- [ ] **Claims:** yearly limits and balances, claim groups, pro-rating for joiners, foreign currency with exchange rates, GST fields, cost centres, drafts, approvers adjust the amount, approver view of entitlement. · L
- [ ] **Receipt reading (OCR)** to fill amount, date and merchant. · M
- [ ] **Mileage from a map route** instead of typing km. · M
- [ ] **Bulk approve / reject** in the approvals inbox. · S
- [ ] **Employee picks their approver** (setting per company, limited to valid approvers). · S

## Phase 7 · Security and sign-in

- [ ] **Two-factor sign-in** by OTP (email or SMS), per company or per role. · M
- [ ] **Trusted devices:** register a device, approve a new sign-in from the trusted one, device list and revoke. · M
- [ ] **Single sign-on:** Microsoft Entra, then Singpass (keys stay on the server). · L
- [ ] **Login rate limits** and account lockout; **persistent signing key** with rotation. · S
- [ ] **Auto sign-out** after inactivity (company setting). · S

## Phase 8 · Mobile app

- [ ] **Home** with real data: today's shift, balances, waiting approvals. · M
- [ ] **Clock in/out** against sites (geofence, Wi-Fi), with an offline queue that syncs later. · M
- [ ] **Requests:** apply for leave and claims (camera for receipts), see status, withdraw. · M
- [ ] **Inbox:** approve, reject or send back. · S
- [ ] **Me:** profile, payslips, documents, language. · M
- [ ] **Push notifications** opening the right screen. · S
- [ ] **App lock** (fingerprint / face / PIN), also asked before opening a payslip, and forced update when a version is too old. · S

## Phase 9 · Face, kiosk and field staff

- [ ] **Face registration and matching** at clock-in, with liveness checks (blur, brightness). · L
- [ ] **Kiosk mode:** a shared tablet where staff clock in by face or card number. · M
- [ ] **Field staff tracking:** background location during working hours for staff marked as field staff, with an offline queue; manager map. · L
- [ ] **Work-from-home zone:** alert when someone leaves their declared location during working hours. · M

## Phase 10 · Extras

- [ ] **Onboarding:** candidate accounts, multi-tab forms with documents (MyKad/IC), HR approval turns them into employees. · L
- [ ] **Training:** course catalogue and sign-up, external course requests with approval, attendance and certificates with expiry. · L
- [ ] **Meeting rooms:** search free rooms, book, change, cancel. · M
- [ ] **AI HR assistant** answering from company policies and the person's own data (balances, schedule, pending requests). · L
- [ ] **Job / project costing:** clock-ins and claims charged to job codes, with corrections and approvals. · M
- [ ] **More languages:** Traditional Chinese (TW/HK) and Thai. · S
- [ ] **Company sign-up** (self-service trial). · M

## Not planned

- Rewards and refer-a-company (INFOTECH's own marketing).
- Voice commands (low value next to the AI assistant).
- Routing notifications by matching their text (ours use structured links).
- A catch-all API route (flagged as a security risk in the INFOTECH report).
