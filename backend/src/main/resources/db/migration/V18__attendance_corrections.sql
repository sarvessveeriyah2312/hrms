-- Attendance corrections ("I forgot to clock out"): someone asks for a day's first clock-in and/or last clock-out to be
-- set to a given time, with a reason. Approved through approval workflows (subject CORRECTION).
-- Clock-ins themselves are never changed: an approved correction replaces those times when the day is worked out,
-- so the original clock-ins stay for the record. Times are local to the person's legal entity.

create table attendance_correction (
    id               uuid primary key,
    tenant_id        uuid not null references tenant(id),
    employee_id      uuid not null references employee(id),
    work_date        date not null,
    clock_in         time,
    clock_out        time,
    reason           text not null,
    status           text not null check (status in ('PENDING', 'APPROVED', 'REJECTED', 'RETURNED', 'CANCELLED')),
    workflow_id      uuid references workflow_instance(id),
    returned_comment text,
    created_at       timestamptz not null default now(),
    decided_at       timestamptz,
    version          bigint not null default 0,
    check (clock_in is not null or clock_out is not null),
    check (clock_in is null or clock_out is null or clock_out > clock_in)
);
create index attendance_correction_day on attendance_correction (tenant_id, employee_id, work_date);
-- One open request per person and day.
create unique index attendance_correction_open on attendance_correction (employee_id, work_date)
    where status in ('PENDING', 'RETURNED');

alter table attendance_correction enable row level security;
alter table attendance_correction force row level security;
create policy tenant_isolation on attendance_correction
    using (tenant_id::text = current_setting('app.tenant_id', true))
    with check (tenant_id::text = current_setting('app.tenant_id', true));

-- Approval workflows can be configured for corrections too (conditions: department only).
alter table approval_flow drop constraint approval_flow_subject_type_check;
alter table approval_flow add constraint approval_flow_subject_type_check check (subject_type in ('LEAVE', 'CLAIM', 'CORRECTION'));
