-- Employee profile (roadmap phase 2): job details, profile sections, change requests, documents and assets.

-- ---------- job details (HR) ----------
alter table employee add column job_title        text;
alter table employee add column employment_type  text check (employment_type in ('PERMANENT', 'CONTRACT', 'PART_TIME', 'INTERN', 'TEMPORARY'));
alter table employee add column confirmation_due date;      -- end of probation
alter table employee add column confirmed_on     date;
alter table employee add column resigned_on      date;      -- notice given
alter table employee add column last_day         date;      -- last working day
alter table employee add column photo_file_id    uuid references file_object(id);
alter table employee add constraint employee_last_day_after_join check (last_day is null or last_day >= join_date);

-- ---------- profile sections ----------
-- One row per person and section (personal, contact, address, bank, emergency, spouse, children, education,
-- national_service). The fields of each section are defined and checked in code (ProfileSections); sensitive values
-- (ID, tax and bank account numbers) are stored encrypted inside the JSON.
create table employee_profile_section (
    tenant_id   uuid not null references tenant(id),
    employee_id uuid not null references employee(id),
    section     text not null,
    data        jsonb not null,
    updated_at  timestamptz not null default now(),
    updated_by  uuid references app_user(id),
    primary key (employee_id, section)
);

-- ---------- change requests ----------
-- An employee's edit to one section, waiting for HR (approval workflow subject PROFILE_CHANGE). "current" is the
-- section as it was when the request was sent, so HR sees the two side by side.
create table employee_change_request (
    id               uuid primary key,
    tenant_id        uuid not null references tenant(id),
    employee_id      uuid not null references employee(id),
    section          text not null,
    proposed         jsonb not null,
    current          jsonb,
    note             text,
    status           text not null check (status in ('PENDING', 'APPROVED', 'REJECTED', 'RETURNED', 'CANCELLED')),
    workflow_id      uuid references workflow_instance(id),
    returned_comment text,
    created_at       timestamptz not null default now(),
    decided_at       timestamptz,
    version          bigint not null default 0
);
create index employee_change_request_emp on employee_change_request (tenant_id, employee_id, created_at desc);
-- One open request per person and section.
create unique index employee_change_request_open on employee_change_request (employee_id, section)
    where status in ('PENDING', 'RETURNED');

-- ---------- documents ----------
create table employee_document (
    id          uuid primary key,
    tenant_id   uuid not null references tenant(id),
    employee_id uuid not null references employee(id),
    file_id     uuid not null references file_object(id),
    category    text not null check (category in ('CONTRACT', 'IDENTITY', 'CERTIFICATE', 'LETTER', 'MEDICAL', 'OTHER')),
    description text,
    uploaded_by uuid references app_user(id),
    created_at  timestamptz not null default now()
);
create index employee_document_emp on employee_document (tenant_id, employee_id, created_at desc);

-- ---------- assets ----------
create table employee_asset (
    id          uuid primary key,
    tenant_id   uuid not null references tenant(id),
    employee_id uuid not null references employee(id),
    kind        text not null check (kind in ('LAPTOP', 'PHONE', 'ACCESS_CARD', 'VEHICLE', 'UNIFORM', 'OTHER')),
    name        text not null,
    tag         text,                   -- serial or asset tag
    issued_on   date not null,
    returned_on date,
    note        text,
    created_at  timestamptz not null default now(),
    updated_by  uuid references app_user(id),
    version     bigint not null default 0,
    check (returned_on is null or returned_on >= issued_on)
);
create index employee_asset_emp on employee_asset (tenant_id, employee_id, issued_on desc);

do $$
declare t text;
begin
    foreach t in array array['employee_profile_section', 'employee_change_request', 'employee_document', 'employee_asset'] loop
        execute format('alter table %I enable row level security', t);
        execute format('alter table %I force row level security', t);
        execute format($p$create policy tenant_isolation on %I
            using (tenant_id::text = current_setting('app.tenant_id', true))
            with check (tenant_id::text = current_setting('app.tenant_id', true))$p$, t);
    end loop;
end $$;

-- "Approve profile changes" (employee:approve): roles that edit employee records get it at the same scope.
insert into role_permission (role_id, permission, data_scope)
select role_id, 'employee:approve', data_scope
from role_permission where permission = 'employee:manage'
on conflict (role_id, permission) do nothing;
