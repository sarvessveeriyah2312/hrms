-- Per-person entitlements and the rules that set them automatically.
--
-- A person's entitlement for a type and year is, in order of precedence:
--   1. leave_entitlement.entitled, when HR has set it for that person (manual);
--   2. otherwise the type's policy: default_days, or the service tier they've reached by 1 January,
--      pro-rated in the year they join when pro_rate is on.
-- Carried days come from leave_entitlement.carried: typed by HR (MANUAL) or written by the year-end run (YEAR_END).

alter table leave_entitlement alter column entitled drop not null;          -- null: use the policy
alter table leave_entitlement alter column carried drop default;
alter table leave_entitlement alter column carried set default 0;
alter table leave_entitlement add column carried_source text check (carried_source in ('MANUAL', 'YEAR_END'));
alter table leave_entitlement add column note text;
alter table leave_entitlement add column updated_at timestamptz not null default now();
alter table leave_entitlement add column updated_by uuid;

alter table leave_type add column pro_rate boolean not null default false;  -- first year: months left / 12
alter table leave_type add column carry_max numeric(5,1) not null default 0 check (carry_max between 0 and 365);

-- "After N years of service, D days a year." The highest tier reached applies.
create table leave_service_tier (
    leave_type_id uuid not null references leave_type(id) on delete cascade,
    tenant_id     uuid not null references tenant(id),
    min_years     integer not null check (min_years between 1 and 50),
    days          numeric(5,1) not null check (days between 0 and 365),
    primary key (leave_type_id, min_years)
);

-- One row per year-end carry-over run (re-running replaces the carried days it wrote).
create table leave_year_end (
    tenant_id   uuid not null references tenant(id),
    year        integer not null,
    run_at      timestamptz not null default now(),
    run_by      uuid,
    people      integer not null,
    days        numeric(8,1) not null,
    primary key (tenant_id, year)
);

do $$
declare t text;
begin
    foreach t in array array['leave_service_tier', 'leave_year_end']
    loop
        execute format('alter table %I enable row level security', t);
        execute format('alter table %I force row level security', t);
        execute format(
            'create policy tenant_isolation on %I using (tenant_id::text = current_setting(''app.tenant_id'', true)) '
            'with check (tenant_id::text = current_setting(''app.tenant_id'', true))', t);
    end loop;
end $$;
