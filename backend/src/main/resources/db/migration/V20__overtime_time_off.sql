-- Overtime paid as money or given back as time off (time off in lieu), set by rules HR configures.
--
-- overtime_rule: how overtime is compensated. One company-wide rule (no entity, no department), and optional rules for
-- a legal entity or a department. A person gets their department's rule, else their entity's, else the company's;
-- with no rule at all, overtime is paid.
--   compensation   PAY (money), TIME_OFF (leave), CHOICE (the employee picks on each request)
--   conversion     BANKED: approved hours add up, and each full block becomes leave (leftover hours wait for more);
--                  PER_REQUEST: each request converts on its own, and hours short of a block are not given as leave
--   credit_unit    HALF_DAY or FULL_DAY, earned for every hours_per_unit hours (e.g. 4 hours = half a day)
--   apply_rate     the rate multiplies the hours first (4 h at 2.0× counts as 8 h)
--   expiry         NONE, DAYS (expiry_days after it's earned) or YEAR_END (31 Dec of the year it's earned).
--                  Earned leave counts in the leave year it's earned; what's left at year end follows the leave
--                  type's carry-forward rules.

create table overtime_rule (
    id             uuid primary key default gen_random_uuid(),
    tenant_id      uuid not null references tenant(id),
    entity_id      uuid references legal_entity(id) on delete cascade,
    department_id  uuid references department(id) on delete cascade,
    compensation   text not null check (compensation in ('PAY', 'TIME_OFF', 'CHOICE')),
    conversion     text not null default 'BANKED' check (conversion in ('BANKED', 'PER_REQUEST')),
    credit_unit    text not null default 'HALF_DAY' check (credit_unit in ('HALF_DAY', 'FULL_DAY')),
    hours_per_unit numeric(4, 2) not null default 4 check (hours_per_unit > 0 and hours_per_unit <= 24),
    apply_rate     boolean not null default false,
    leave_type_id  uuid references leave_type(id),
    expiry         text not null default 'NONE' check (expiry in ('NONE', 'DAYS', 'YEAR_END')),
    expiry_days    integer check (expiry_days between 1 and 730),
    updated_at     timestamptz not null default now(),
    check (entity_id is null or department_id is null),
    check (compensation = 'PAY' or leave_type_id is not null),
    check (expiry <> 'DAYS' or expiry_days is not null)
);
create unique index overtime_rule_company on overtime_rule (tenant_id) where entity_id is null and department_id is null;
create unique index overtime_rule_entity on overtime_rule (tenant_id, entity_id) where entity_id is not null;
create unique index overtime_rule_department on overtime_rule (tenant_id, department_id) where department_id is not null;

-- Each request says how it's compensated. When approved as time off: the hours that counted (after the rate), and the
-- leave given for it (credited_days; 0 while the hours wait in the bank).
alter table overtime_request
    add column compensation text not null default 'PAY' check (compensation in ('PAY', 'TIME_OFF')),
    add column credited_hours numeric(5, 2),
    add column credited_days numeric(4, 1),
    add column leave_type_id uuid references leave_type(id);
-- The rate only matters when it's paid (or applied to time off), so it may be left out.
alter table overtime_request alter column rate drop not null;

-- Banked hours not yet turned into leave, per person and leave type.
create table overtime_bank (
    tenant_id     uuid not null references tenant(id),
    employee_id   uuid not null references employee(id),
    leave_type_id uuid not null references leave_type(id),
    hours         numeric(6, 2) not null default 0 check (hours >= 0),
    updated_at    timestamptz not null default now(),
    primary key (employee_id, leave_type_id)
);

-- Leave earned on top of the yearly entitlement (from overtime for now), counted in the leave year of earned_on.
create table leave_credit (
    id            uuid primary key default gen_random_uuid(),
    tenant_id     uuid not null references tenant(id),
    employee_id   uuid not null references employee(id),
    leave_type_id uuid not null references leave_type(id),
    days          numeric(4, 1) not null check (days > 0),
    earned_on     date not null,
    expires_on    date,
    source_type   text not null,            -- OVERTIME
    source_id     uuid,
    note          text,
    created_at    timestamptz not null default now()
);
create index leave_credit_balance on leave_credit (tenant_id, employee_id, leave_type_id, earned_on);

do $$
declare t text;
begin
    foreach t in array array['overtime_rule', 'overtime_bank', 'leave_credit'] loop
        execute format('alter table %I enable row level security', t);
        execute format('alter table %I force row level security', t);
        execute format($p$create policy tenant_isolation on %I
            using (tenant_id::text = current_setting('app.tenant_id', true))
            with check (tenant_id::text = current_setting('app.tenant_id', true))$p$, t);
    end loop;
end $$;
