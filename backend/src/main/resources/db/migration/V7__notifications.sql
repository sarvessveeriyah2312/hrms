-- In-app notifications: one row per recipient, read when they open it.
create table notification (
    id                uuid primary key default gen_random_uuid(),
    tenant_id         uuid not null references tenant(id),
    recipient_user_id uuid not null references app_user(id) on delete cascade,
    kind              text not null,         -- APPROVAL_WAITING, REQUEST_APPROVED, REQUEST_REJECTED, REQUEST_RETURNED, ...
    title             text not null,
    body              text,
    link              text not null,         -- path inside the web app, e.g. /app/approvals?open=<id>
    tone              text not null default 'blue',
    created_at        timestamptz not null default now(),
    read_at           timestamptz
);
create index notification_inbox on notification (recipient_user_id, created_at desc);
create index notification_unread on notification (recipient_user_id) where read_at is null;

alter table notification enable row level security;
alter table notification force row level security;
create policy tenant_isolation on notification
    using (tenant_id::text = current_setting('app.tenant_id', true))
    with check (tenant_id::text = current_setting('app.tenant_id', true));
