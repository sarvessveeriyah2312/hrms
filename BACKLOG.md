# Backlog

Work deferred on purpose. Pick items from here when planning the next round.

## Notifications (follow-ups to in-app notifications)

- [ ] **Email and push.** Same events as in-app, through email (local test mailbox such as Mailpit) and mobile push (the Firebase `PushSender` already exists). Needs per-person on/off settings per event.
- [ ] **Backfill.** Requests created before notifications existed never produced one. Optionally create "waiting for you" notifications for steps that are still active.
- [ ] **Overdue reminders and backup alerts.** Needs a scheduled job: remind the approver when a step passes `due_at`, and tell the requester's backup approver when they can now act (approver away or step overdue).
- [ ] **Stale approver alerts.** When a request is withdrawn or decided by someone else, mark the other approvers' "waiting for you" notifications as resolved, so they don't linger. (Today, opening one says the request is no longer waiting.)

## Approval workflows (follow-ups)

- [ ] **Use workflows for claims, attendance corrections and overtime** when those modules exist (the engine and builder are generic; add the subject type and its conditions, e.g. claim amount).
- [ ] **More conditions:** entity, employee role, half day vs full day.
- [ ] **Parallel steps** ("any two of three approvers") and **conditional steps** inside one workflow.
- [ ] **Reassign a stuck step** when an approver has left; admin view of all requests in progress.
