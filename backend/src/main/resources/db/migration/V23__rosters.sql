-- Scheduling: shift templates, and weekly rosters per site.
--
-- roster_shift is the planner's working copy. Publishing a site's week copies it to published_shift, which is what
-- employees, Today and timesheets read; unpublishing deletes the published copy. roster_week keeps each site's week
-- status: when it was last published, and when the working copy last changed (changed after publishing = unpublished
-- changes). Times are local to the person's legal entity; a shift ends on the day it starts (no overnight shifts yet).

create table shift_template (
    id          uuid primary key default gen_random_uuid(),
    tenant_id   uuid not null references tenant(id),
    name        text not null,
    code        text not null,                                    -- short label on the roster, e.g. "AM"
    start_time  time not null,
    end_time    time not null,
    break_min   smallint not null default 60 check (break_min between 0 and 240),
    grace_min   smallint not null default 5 check (grace_min between 0 and 120),
    tone        text not null default 'blue',
    active      boolean not null default true,
    created_at  timestamptz not null default now(),
    check (end_time > start_time)
);
create unique index shift_template_name on shift_template (tenant_id, lower(name));

create table roster_shift (
    id          uuid primary key default gen_random_uuid(),
    tenant_id   uuid not null references tenant(id),
    site_id     uuid not null references site(id) on delete cascade,
    employee_id uuid not null references employee(id) on delete cascade,
    work_date   date not null,
    start_time  time not null,
    end_time    time not null,
    break_min   smallint not null default 0 check (break_min between 0 and 240),
    grace_min   smallint not null default 5 check (grace_min between 0 and 120),
    template_id uuid references shift_template(id) on delete set null,
    code        text,
    tone        text not null default 'blue',
    note        text,
    updated_at  timestamptz not null default now(),
    check (end_time > start_time)
);
create index roster_shift_week on roster_shift (tenant_id, site_id, work_date);
create index roster_shift_person on roster_shift (tenant_id, employee_id, work_date);

create table published_shift (
    id          uuid primary key,                                 -- the roster_shift it was published from
    tenant_id   uuid not null references tenant(id),
    site_id     uuid not null references site(id) on delete cascade,
    employee_id uuid not null references employee(id) on delete cascade,
    work_date   date not null,
    start_time  time not null,
    end_time    time not null,
    break_min   smallint not null,
    grace_min   smallint not null,
    code        text,
    tone        text not null,
    note        text
);
create index published_shift_person on published_shift (tenant_id, employee_id, work_date);
create index published_shift_week on published_shift (tenant_id, site_id, work_date);

create table roster_week (
    tenant_id     uuid not null references tenant(id),
    site_id       uuid not null references site(id) on delete cascade,
    week_start    date not null check (extract(isodow from week_start) = 1),
    published_at  timestamptz,
    published_by  uuid references app_user(id),
    changed_at    timestamptz,
    primary key (tenant_id, site_id, week_start)
);

do $$
declare t text;
begin
    foreach t in array array['shift_template', 'roster_shift', 'published_shift', 'roster_week'] loop
        execute format('alter table %I enable row level security', t);
        execute format('alter table %I force row level security', t);
        execute format($p$create policy tenant_isolation on %I
            using (tenant_id::text = current_setting('app.tenant_id', true))
            with check (tenant_id::text = current_setting('app.tenant_id', true))$p$, t);
    end loop;
end $$;

-- "Plan rosters" (attendance:roster): roles that see timesheets for others get it at the same scope.
insert into role_permission (role_id, permission, data_scope)
select role_id, 'attendance:roster', data_scope
from role_permission where permission = 'attendance:timesheets' and data_scope <> 'SELF'
on conflict (role_id, permission) do nothing;
