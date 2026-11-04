-- Approval workflow engine and the leave module (its first user).

-- ---------- workflow ----------
-- A step is either assigned to one employee (a manager) or to the HR pool (anyone with company-wide approve rights).
alter table workflow_step alter column assignee_id drop not null;
alter table workflow_step add column pool text check (pool in ('HR'));
alter table workflow_step add column state text not null default 'WAITING'
    check (state in ('WAITING', 'ACTIVE', 'DONE', 'SKIPPED'));
alter table workflow_step add column acted_as text check (acted_as in ('ASSIGNEE', 'BACKUP', 'HR', 'OVERRIDE', 'AUTO'));
alter table workflow_step add column activated_at timestamptz;
alter table workflow_step add constraint workflow_step_target check (assignee_id is not null or pool is not null);

alter table workflow_instance add column module text;                       -- permission module, e.g. 'leave'
alter table workflow_instance add column requester_id uuid references employee(id);
alter table workflow_instance add column decided_at timestamptz;
alter table workflow_instance add constraint workflow_instance_state
    check (state in ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED'));

create index workflow_step_active on workflow_step (tenant_id, state, assignee_id);
create index workflow_step_instance on workflow_step (instance_id, level);
create index workflow_instance_subject on workflow_instance (tenant_id, subject_type, subject_id);

-- ---------- leave ----------
create table leave_type (
    id               uuid primary key default gen_random_uuid(),
    tenant_id        uuid not null references tenant(id),
    code             text not null,
    name             text not null,
    tone             text not null default 'teal',        -- colour in the UI
    paid             boolean not null default true,
    track_balance    boolean not null default true,       -- false: no entitlement (e.g. unpaid leave)
    default_days     numeric(5,1) not null default 0,     -- yearly entitlement unless set per employee
    half_day_allowed boolean not null default true,
    approval_levels  smallint not null default 1 check (approval_levels between 0 and 2),  -- manager, manager's manager
    hr_approval      boolean not null default false,      -- add an HR step after the managers
    active           boolean not null default true,
    created_at       timestamptz not null default now(),
    unique (tenant_id, code)
);

-- Per-employee entitlement for a year; when missing, the type's default_days applies.
create table leave_entitlement (
    tenant_id     uuid not null references tenant(id),
    employee_id   uuid not null references employee(id),
    leave_type_id uuid not null references leave_type(id),
    year          integer not null,
    entitled      numeric(5,1) not null,
    carried       numeric(5,1) not null default 0,
    primary key (employee_id, leave_type_id, year)
);

create table leave_request (
    id            uuid primary key,
    tenant_id     uuid not null references tenant(id),
    employee_id   uuid not null references employee(id),
    leave_type_id uuid not null references leave_type(id),
    start_date    date not null,
    end_date      date not null,
    day_part      text not null default 'FULL' check (day_part in ('FULL', 'AM', 'PM')),
    days          numeric(5,1) not null check (days > 0),
    reason        text,
    status        text not null check (status in ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')),
    workflow_id   uuid references workflow_instance(id),
    created_at    timestamptz not null default now(),
    decided_at    timestamptz,
    version       bigint not null default 0,
    check (end_date >= start_date)
);
create index leave_request_employee on leave_request (tenant_id, employee_id, start_date);
create index leave_request_dates on leave_request (tenant_id, start_date, end_date) where status in ('PENDING', 'APPROVED');

create table public_holiday (
    id        uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenant(id),
    date      date not null,
    name      text not null,
    unique (tenant_id, date)
);

do $$
declare t text;
begin
    foreach t in array array['leave_type', 'leave_entitlement', 'leave_request', 'public_holiday']
    loop
        execute format('alter table %I enable row level security', t);
        execute format('alter table %I force row level security', t);
        execute format(
            'create policy tenant_isolation on %I using (tenant_id::text = current_setting(''app.tenant_id'', true)) '
            'with check (tenant_id::text = current_setting(''app.tenant_id'', true))', t);
    end loop;
end $$;
