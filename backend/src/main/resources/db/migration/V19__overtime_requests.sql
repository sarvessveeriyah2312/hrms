-- Overtime requests: someone asks to be paid for extra hours worked on a day, at a rate (1.0×, 1.5×, 2.0× or 3.0×),
-- with a reason. Approved through approval workflows (subject OVERTIME). Approved hours show on timesheets.
-- Hours come in quarter-hour steps. A person may have several requests for one day (e.g. some hours at 1.5× and
-- some at 2.0×), as long as the day's open and approved hours add up to 24 at most (checked by the service).

create table overtime_request (
    id               uuid primary key,
    tenant_id        uuid not null references tenant(id),
    employee_id      uuid not null references employee(id),
    work_date        date not null,
    hours            numeric(4, 2) not null check (hours > 0 and hours <= 24 and hours * 4 = trunc(hours * 4)),
    rate             numeric(2, 1) not null check (rate in (1.0, 1.5, 2.0, 3.0)),
    reason           text not null,
    status           text not null check (status in ('PENDING', 'APPROVED', 'REJECTED', 'RETURNED', 'CANCELLED')),
    workflow_id      uuid references workflow_instance(id),
    returned_comment text,
    created_at       timestamptz not null default now(),
    decided_at       timestamptz,
    version          bigint not null default 0
);
create index overtime_request_day on overtime_request (tenant_id, employee_id, work_date);

alter table overtime_request enable row level security;
alter table overtime_request force row level security;
create policy tenant_isolation on overtime_request
    using (tenant_id::text = current_setting('app.tenant_id', true))
    with check (tenant_id::text = current_setting('app.tenant_id', true));

-- Approval workflows for overtime (conditions: department and hours).
alter table approval_flow drop constraint approval_flow_subject_type_check;
alter table approval_flow add constraint approval_flow_subject_type_check
    check (subject_type in ('LEAVE', 'CLAIM', 'CORRECTION', 'OVERTIME'));
