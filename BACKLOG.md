# Backlog

Work deferred on purpose. Pick items from here when planning the next round. New modules and features are in [ROADMAP.md](ROADMAP.md).

## Notifications (follow-ups to in-app notifications)

- [x] **Email and push.** Same events as in-app, through email (local test mailbox such as Mailpit) and mobile push (the Firebase `PushSender` already exists). Needs per-person on/off settings per event.
- [ ] **Backfill.** Requests created before notifications existed never produced one. Optionally create "waiting for you" notifications for steps that are still active.
- [x] **Overdue reminders and backup alerts.** Needs a scheduled job: remind the approver when a step passes `due_at`, and tell the requester's backup approver when they can now act (approver away or step overdue).
- [ ] **Stale approver alerts.** When a request is withdrawn or decided by someone else, mark the other approvers' "waiting for you" notifications as resolved, so they don't linger. (Today, opening one says the request is no longer waiting.)

## Approval workflows (follow-ups)

- [ ] **Overnight shifts** (22:00–06:00): shifts end on the day they start; overnight needs timesheets to count a session across midnight.
- [ ] **Roles / skills on rosters** ("needs 2 cashiers"): then the publish check can flag missing roles.
- [ ] **Roster templates** (a whole standard week) and **auto-fill** from availability.
- [ ] **Mobile:** My schedule and shift notifications in the app.
- [ ] **Lock per company entity:** locks are company-wide; entities paid on different dates may want their own.
- [ ] **Attendance report for big companies:** it works a year out month by month (up to 500 people per month, like timesheets); store monthly totals if that gets slow.
- [ ] **Undo time off for overtime:** HR can't yet reverse an approved overtime request (and the leave or banked hours it gave); today they'd adjust the person's entitlement by hand.
- [ ] **Earned leave across years:** time off earned for overtime counts in the leave year it's earned in, so a "90 days" expiry is cut short at year end unless the leave type carries days forward. Count it in each year it's usable instead.
- [ ] **Bank balance for HR:** a screen showing each person's banked overtime hours, with manual adjustments.
- [ ] **Bulk approve for every request type** in the inbox (the `POST /approvals/approve` endpoint is generic; the inbox only offers it for overtime so far).
- [ ] **More conditions:** entity, employee role, half day vs full day.
- [ ] **Parallel steps** ("any two of three approvers") and **conditional steps** inside one workflow.
- [ ] **Reassign a stuck step** when an approver has left; admin view of all requests in progress.

## Claims (follow-ups)

- [ ] **Read the receipt.** Fill in the amount, date and merchant from the receipt photo (OCR), as the landing page promises.
- [ ] **Work out mileage from the route.** Today people type the distance; calculate it from the from/to places with a maps service.
- [ ] **Expense reports:** several expenses in one claim, and claims in a foreign currency with an exchange rate.
- [ ] **HR records a claim for someone**, and HR corrects or cancels an approved claim before it's paid.
- [ ] **Per-entity claim limits and mileage rates** (today a claim type's limits and rate apply company-wide).
- [ ] **Mobile:** submit a claim with the phone camera.

## Employee profile (follow-ups)

- [ ] **Profile changes through approval workflows:** they always go to HR (anyone with company-wide `employee:approve`); a **Profile changes** subject in Workflows would let companies add the manager or route by department.
- [ ] **Fields per country:** every company sees every section (national service is Singapore-only, ID types cover MY, SG, HK and India). Hide sections and options that don't apply to the person's entity country.
- [ ] **Document expiry** (passports, work permits, certificates) with reminders before they run out.
- [ ] **Leavers:** a last working day doesn't switch the account off yet; disable sign-in (and stop clock-ins) automatically the day after.
- [ ] **Bulk import** of profile data and job details from a spreadsheet when a company starts.

## Payroll (follow-ups)

- [ ] **Investment declarations (India):** employees declare tax-saving investments (80C, 80D, HRA) with proofs for HR to verify before TDS is worked out.
- [ ] **Amounts on uploaded payslips:** only generated payslips carry gross and net pay, so the dashboard's payroll cost leaves uploaded PDFs out. Read them from a summary CSV, or let HR type them in.
- [ ] **Salary records:** pay, allowances and statutory schemes (EPF, SOCSO, EIS, PCB; CPF; MPF) per person, so payslips can be worked out here rather than imported.
- [ ] **Payslip fonts:** generated payslips use the standard PDF fonts, so Chinese and Tamil names show as "?". Embed a Unicode font.
- [ ] **Scheduled publishing** (publish on pay day at 9:00) and a reminder to people who haven't opened their payslip.

## Communication (follow-ups)

- [ ] **Rich text in notices** (bold, lists, links); today they're plain text with paragraphs.
- [ ] **Scheduled notices** (publish at a set time) and expiry dates for announcements.
- [ ] **Letters signed electronically** by the employee (accept an offer, acknowledge a warning) instead of print and sign.
- [ ] **Grievance categories routed to different handlers** (e.g. harassment to a named committee) and response-time targets.
- [ ] **Email digest** (one daily email instead of one per notification) and quiet hours for push.
