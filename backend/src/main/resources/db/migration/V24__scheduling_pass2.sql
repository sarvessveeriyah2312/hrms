-- Scheduling, pass 2: availability, open shifts, shift swaps and give-aways, and overtime asked for in advance.

-- Weekly availability. One row per person and weekday they've set: ANY (any time), NONE (not available) or WINDOWS
-- (up to three windows, starts[i]–ends[i]). A weekday without a row is ANY.
create table availability_day (
    tenant_id   uuid not null references tenant(id),
    employee_id uuid not null references employee(id) on delete cascade,
    weekday     smallint not null check (weekday between 1 and 7),
    mode        text not null check (mode in ('ANY', 'NONE', 'WINDOWS')),
    starts      time[] not null default '{}',
    ends        time[] not null default '{}',
    updated_at  timestamptz not null default now(),
    primary key (employee_id, weekday),
    check (cardinality(starts) = cardinality(ends) and cardinality(starts) <= 3),
    check (mode <> 'WINDOWS' or cardinality(starts) >= 1)
);

-- Open shifts: a shift with nobody on it yet, which people at the site can claim once it's published.
alter table roster_shift alter column employee_id drop not null;
alter table published_shift alter column employee_id drop not null;

-- Asking to change who works a published shift.
--   GIVE: give my shift to a colleague. SWAP: my shift for one of theirs. CLAIM: take an open shift.
--   GIVE and SWAP wait for the colleague (COLLEAGUE), then go for approval (PENDING); CLAIM goes straight to approval.
create table shift_request (
    id                 uuid primary key,
    tenant_id          uuid not null references tenant(id),
    kind               text not null check (kind in ('GIVE', 'SWAP', 'CLAIM')),
    requester_id       uuid not null references employee(id),
    shift_id           uuid not null,                      -- the published shift (same id as its roster_shift)
    colleague_id       uuid references employee(id),
    colleague_shift_id uuid,                               -- SWAP: the colleague's shift
    reason             text,
    status             text not null check (status in ('COLLEAGUE', 'PENDING', 'APPROVED', 'REJECTED', 'RETURNED',
                                                       'DECLINED', 'CANCELLED')),
    workflow_id        uuid references workflow_instance(id),
    returned_comment   text,
    created_at         timestamptz not null default now(),
    decided_at         timestamptz,
    check (kind = 'CLAIM' or colleague_id is not null),
    check (kind <> 'SWAP' or colleague_shift_id is not null)
);
create index shift_request_shift on shift_request (tenant_id, shift_id);
create index shift_request_people on shift_request (tenant_id, requester_id);
create index shift_request_colleague on shift_request (tenant_id, colleague_id);
-- One open request per person and shift.
create unique index shift_request_open on shift_request (requester_id, shift_id) where status in ('COLLEAGUE', 'PENDING', 'RETURNED');

do $$
declare t text;
begin
    foreach t in array array['availability_day', 'shift_request'] loop
        execute format('alter table %I enable row level security', t);
        execute format('alter table %I force row level security', t);
        execute format($p$create policy tenant_isolation on %I
            using (tenant_id::text = current_setting('app.tenant_id', true))
            with check (tenant_id::text = current_setting('app.tenant_id', true))$p$, t);
    end loop;
end $$;

-- Approval workflows for shift changes (conditions: department only).
alter table approval_flow drop constraint approval_flow_subject_type_check;
alter table approval_flow add constraint approval_flow_subject_type_check
    check (subject_type in ('LEAVE', 'CLAIM', 'CORRECTION', 'OVERTIME', 'SHIFT_CHANGE'));

-- Overtime asked for before it's worked: a rule can require it; requests say whether they were (planned).
alter table overtime_rule add column require_pre_approval boolean not null default false;
alter table overtime_request add column planned boolean not null default false;
