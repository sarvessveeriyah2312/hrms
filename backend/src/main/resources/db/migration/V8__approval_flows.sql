-- Configurable approval workflows: admins build named flows with conditions and ordered steps.
-- A request uses the first active flow (by position) whose conditions match; with none, the leave type's
-- own simple chain applies as before.

create table approval_flow (
    id             uuid primary key default gen_random_uuid(),
    tenant_id      uuid not null references tenant(id),
    subject_type   text not null default 'LEAVE' check (subject_type in ('LEAVE')),
    name           text not null,
    description    text,
    active         boolean not null default true,
    position       integer not null default 0,           -- checked in this order; first match wins
    -- Conditions; null means "any".
    type_ids       uuid[],                                -- leave types (claim types later)
    department_ids uuid[],
    min_quantity   numeric(8,1),                          -- days for leave (amount for claims later)
    max_quantity   numeric(8,1),
    created_at     timestamptz not null default now(),
    updated_at     timestamptz not null default now(),
    unique (tenant_id, name)
);

create table approval_flow_step (
    id            uuid primary key default gen_random_uuid(),
    tenant_id     uuid not null references tenant(id),
    flow_id       uuid not null references approval_flow(id) on delete cascade,
    position      integer not null,
    kind          text not null check (kind in ('MANAGER', 'PERSON', 'ROLE', 'HR')),
    manager_level smallint check (manager_level between 1 and 3),   -- MANAGER: 1 = their manager, 2 = manager's manager…
    employee_id   uuid references employee(id) on delete cascade,     -- PERSON
    role_id       uuid references role(id) on delete cascade,         -- ROLE: any member may act
    unique (flow_id, position),
    check ((kind = 'MANAGER' and manager_level is not null)
        or (kind = 'PERSON' and employee_id is not null)
        or (kind = 'ROLE' and role_id is not null)
        or kind = 'HR')
);

-- Workflow steps can now be owned by a role, and remember what kind of step they were.
alter table workflow_step drop constraint workflow_step_pool_check;
alter table workflow_step add constraint workflow_step_pool_check check (pool in ('HR', 'ROLE'));
alter table workflow_step add column pool_role_id uuid references role(id) on delete set null;
alter table workflow_step add column kind text check (kind in ('MANAGER', 'PERSON', 'ROLE', 'HR'));
alter table workflow_step drop constraint workflow_step_acted_as_check;
alter table workflow_step add constraint workflow_step_acted_as_check
    check (acted_as in ('ASSIGNEE', 'BACKUP', 'HR', 'ROLE', 'OVERRIDE', 'AUTO'));

alter table workflow_instance add column flow_id uuid references approval_flow(id) on delete set null;
alter table workflow_instance add column flow_name text;

do $$
declare t text;
begin
    foreach t in array array['approval_flow', 'approval_flow_step']
    loop
        execute format('alter table %I enable row level security', t);
        execute format('alter table %I force row level security', t);
        execute format(
            'create policy tenant_isolation on %I using (tenant_id::text = current_setting(''app.tenant_id'', true)) '
            'with check (tenant_id::text = current_setting(''app.tenant_id'', true))', t);
    end loop;
end $$;
