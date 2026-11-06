-- "See attendance reports" (reports:attendance): every role that can see leave reports gets it at the same scope;
-- admins can then take it away.
insert into role_permission (role_id, permission, data_scope)
select role_id, 'reports:attendance', data_scope
from role_permission where permission = 'reports:leave'
on conflict (role_id, permission) do nothing;

-- "Lock attendance periods" (attendance:lock): roles that manage attendance rules get it.
insert into role_permission (role_id, permission, data_scope)
select role_id, 'attendance:lock', 'TENANT'
from role_permission where permission = 'attendance:manage'
on conflict (role_id, permission) do nothing;

-- A locked month: its timesheets, corrections and overtime can't change (company-wide, for payroll).
-- period is the first day of the month. Unlocking deletes the row; every lock and unlock is kept in the history.
create table attendance_lock (
    tenant_id  uuid not null references tenant(id),
    period     date not null check (extract(day from period) = 1),
    locked_by  uuid references app_user(id),
    locked_at  timestamptz not null default now(),
    primary key (tenant_id, period)
);

create table attendance_lock_event (
    id         uuid primary key default gen_random_uuid(),
    tenant_id  uuid not null references tenant(id),
    period     date not null,
    action     text not null check (action in ('LOCK', 'UNLOCK')),
    reason     text,
    actor      uuid references app_user(id),
    at         timestamptz not null default now()
);
create index attendance_lock_event_period on attendance_lock_event (tenant_id, period, at);

do $$
declare t text;
begin
    foreach t in array array['attendance_lock', 'attendance_lock_event'] loop
        execute format('alter table %I enable row level security', t);
        execute format('alter table %I force row level security', t);
        execute format($p$create policy tenant_isolation on %I
            using (tenant_id::text = current_setting('app.tenant_id', true))
            with check (tenant_id::text = current_setting('app.tenant_id', true))$p$, t);
    end loop;
end $$;
