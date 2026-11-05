# Backlog

Work deferred on purpose. Pick items from here when planning the next round. New modules and features are in [ROADMAP.md](ROADMAP.md).

## Notifications (follow-ups to in-app notifications)

- [ ] **Email and push.** Same events as in-app, through email (local test mailbox such as Mailpit) and mobile push (the Firebase `PushSender` already exists). Needs per-person on/off settings per event.
- [ ] **Backfill.** Requests created before notifications existed never produced one. Optionally create "waiting for you" notifications for steps that are still active.
- [ ] **Overdue reminders and backup alerts.** Needs a scheduled job: remind the approver when a step passes `due_at`, and tell the requester's backup approver when they can now act (approver away or step overdue).
- [ ] **Stale approver alerts.** When a request is withdrawn or decided by someone else, mark the other approvers' "waiting for you" notifications as resolved, so they don't linger. (Today, opening one says the request is no longer waiting.)

## Approval workflows (follow-ups)

- [ ] **Use workflows for attendance corrections and overtime** when that module exists (claims are done; the engine and builder are generic: add the subject type and its conditions).
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

