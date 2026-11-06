# Backlog

Work deferred on purpose. Pick items from here when planning the next round. New modules and features are in [ROADMAP.md](ROADMAP.md).

## Notifications (follow-ups to in-app notifications)

- [ ] **Email and push.** Same events as in-app, through email (local test mailbox such as Mailpit) and mobile push (the Firebase `PushSender` already exists). Needs per-person on/off settings per event.
- [ ] **Backfill.** Requests created before notifications existed never produced one. Optionally create "waiting for you" notifications for steps that are still active.
- [ ] **Overdue reminders and backup alerts.** Needs a scheduled job: remind the approver when a step passes `due_at`, and tell the requester's backup approver when they can now act (approver away or step overdue).
- [ ] **Stale approver alerts.** When a request is withdrawn or decided by someone else, mark the other approvers' "waiting for you" notifications as resolved, so they don't linger. (Today, opening one says the request is no longer waiting.)

## Approval workflows (follow-ups)

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

