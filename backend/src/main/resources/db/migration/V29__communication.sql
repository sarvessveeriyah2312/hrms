-- Communication and requests (roadmap phase 5): notices (announcements, HR memos, policies), letters on letterhead,
-- grievances, email/push notification preferences and push devices, and overdue approval reminders.

-- ---------- permissions ----------
-- Roles that edit employee records company-wide run communication.
insert into role_permission (role_id, permission, data_scope)
select rp.role_id, p.permission, 'TENANT'
from role_permission rp
cross join (values ('comms:post'), ('comms:policies'), ('comms:letters'), ('comms:grievances')) as p(permission)
where rp.permission = 'employee:manage' and rp.data_scope = 'TENANT'
on conflict (role_id, permission) do nothing;

-- ---------- notices: announcements, memos and policies ----------
-- audience ALL, DEPARTMENTS (audience_ids are departments) or ENTITIES (legal entities).
-- Policies carry a version: publishing a change bumps it, and acknowledgements are per version.
create table notice (
    id            uuid primary key,
    tenant_id     uuid not null references tenant(id),
    kind          text not null check (kind in ('ANNOUNCEMENT', 'MEMO', 'POLICY')),
    title         text not null,
    summary       text,
    body          text not null default '',
    audience      text not null default 'ALL' check (audience in ('ALL', 'DEPARTMENTS', 'ENTITIES')),
    audience_ids  uuid[] not null default '{}',
    attachment_ids uuid[] not null default '{}',
    pinned        boolean not null default false,
    requires_ack  boolean not null default false,
    version       integer not null default 0,         -- 0 until first published
    status        text not null default 'DRAFT' check (status in ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    published_at  timestamptz,
    published_by  uuid references app_user(id),
    created_by    uuid references app_user(id),
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now(),
    row_version   bigint not null default 0
);
create index notice_list on notice (tenant_id, kind, status, published_at desc);

-- Read receipts and acknowledgements, per person and notice version.
create table notice_receipt (
    tenant_id       uuid not null references tenant(id),
    notice_id       uuid not null references notice(id) on delete cascade,
    version         integer not null,
    employee_id     uuid not null references employee(id),
    read_at         timestamptz not null default now(),
    acknowledged_at timestamptz,
    primary key (notice_id, version, employee_id)
);

-- ---------- letters ----------
-- Company letterhead: what every letter's header and signature show.
create table letterhead (
    tenant_id       uuid primary key references tenant(id),
    company_name    text,
    address         text,
    phone           text,
    email           text,
    website         text,
    registration_no text,
    logo_file_id    uuid references file_object(id),
    signatory_name  text,
    signatory_title text,
    footer          text,
    updated_at      timestamptz not null default now(),
    updated_by      uuid references app_user(id)
);

-- Letter templates with {{placeholders}}; kind groups them (offer, confirmation, increment…).
create table letter_template (
    id         uuid primary key,
    tenant_id  uuid not null references tenant(id),
    kind       text not null check (kind in ('OFFER', 'CONFIRMATION', 'INCREMENT', 'PROMOTION', 'EXPERIENCE', 'WARNING', 'OTHER')),
    name       text not null,
    subject    text not null,
    body       text not null,
    active     boolean not null default true,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    updated_by uuid references app_user(id)
);

-- A letter issued to one person (the PDF is kept; the values used are kept for the record).
create table letter (
    id              uuid primary key,
    tenant_id       uuid not null references tenant(id),
    employee_id     uuid not null references employee(id),
    template_id     uuid references letter_template(id) on delete set null,
    kind            text not null,
    subject         text not null,
    file_id         uuid not null references file_object(id),
    field_values    jsonb not null default '{}',
    issued_by       uuid references app_user(id),
    issued_at       timestamptz not null default now(),
    first_viewed_at timestamptz,
    withdrawn_at    timestamptz
);
create index letter_emp on letter (tenant_id, employee_id, issued_at desc);

-- ---------- grievances and feedback ----------
create table grievance (
    id           uuid primary key,
    tenant_id    uuid not null references tenant(id),
    employee_id  uuid not null references employee(id),
    category     text not null check (category in ('WORKPLACE', 'HARASSMENT', 'PAY', 'MANAGEMENT', 'FACILITIES', 'SUGGESTION', 'OTHER')),
    subject      text not null,
    anonymous    boolean not null default false,         -- handlers don't see who raised it
    status       text not null default 'OPEN' check (status in ('OPEN', 'IN_PROGRESS', 'RESOLVED', 'CLOSED')),
    assigned_to  uuid references employee(id),
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now(),
    closed_at    timestamptz,
    version      bigint not null default 0
);
create index grievance_list on grievance (tenant_id, status, updated_at desc);
create index grievance_emp on grievance (tenant_id, employee_id, created_at desc);

create table grievance_message (
    id           uuid primary key,
    tenant_id    uuid not null references tenant(id),
    grievance_id uuid not null references grievance(id) on delete cascade,
    author_id    uuid references employee(id),
    author_role  text not null check (author_role in ('SUBMITTER', 'HANDLER', 'SYSTEM')),
    body         text not null,
    file_id      uuid references file_object(id),
    created_at   timestamptz not null default now()
);
create index grievance_message_thread on grievance_message (grievance_id, created_at);

-- ---------- notification preferences and push devices ----------
-- One row per person and kind of notification they changed; missing means the defaults (email and push on).
create table notification_preference (
    tenant_id  uuid not null references tenant(id),
    user_id    uuid not null references app_user(id),
    kind       text not null,
    email      boolean not null default true,
    push       boolean not null default true,
    updated_at timestamptz not null default now(),
    primary key (user_id, kind)
);
create unique index device_push_token on device (user_id, push_token) where push_token is not null;

-- ---------- overdue approval reminders ----------
alter table workflow_step add column reminded_at     timestamptz;
alter table workflow_step add column reminder_count  integer not null default 0;
alter table workflow_step add column backup_alerted_at timestamptz;

do $$
declare t text;
begin
    foreach t in array array['notice', 'notice_receipt', 'letterhead', 'letter_template', 'letter', 'grievance',
                             'grievance_message', 'notification_preference'] loop
        execute format('alter table %I enable row level security', t);
        execute format('alter table %I force row level security', t);
        execute format($p$create policy tenant_isolation on %I
            using (tenant_id::text = current_setting('app.tenant_id', true))
            with check (tenant_id::text = current_setting('app.tenant_id', true))$p$, t);
    end loop;
end $$;
