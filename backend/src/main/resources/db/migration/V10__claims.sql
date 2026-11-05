-- Expense claims: claim types (with limits, mileage rate and approval chain) and claims (one expense each).

create table claim_type (
    id               uuid primary key default gen_random_uuid(),
    tenant_id        uuid not null references tenant(id),
    code             text not null,
    name             text not null,
    tone             text not null default 'orange',
    kind             text not null default 'RECEIPT' check (kind in ('RECEIPT', 'MILEAGE')),
    max_per_claim    numeric(12,2) check (max_per_claim > 0),     -- null: no limit
    max_per_month    numeric(12,2) check (max_per_month > 0),     -- per person, by expense month; null: no limit
    rate_per_km      numeric(8,4) check (rate_per_km > 0),        -- MILEAGE: amount = km × rate
    receipt_required boolean not null default true,              -- RECEIPT types only
    approval_levels  smallint not null default 1 check (approval_levels between 0 and 2),
    hr_approval      boolean not null default false,
    active           boolean not null default true,
    created_at       timestamptz not null default now(),
    unique (tenant_id, code),
    check (kind <> 'MILEAGE' or rate_per_km is not null)
);

create table claim (
    id               uuid primary key,
    tenant_id        uuid not null references tenant(id),
    employee_id      uuid not null references employee(id),
    claim_type_id    uuid not null references claim_type(id),
    expense_date     date not null,
    description      text not null,
    merchant         text,
    amount           numeric(12,2) not null check (amount > 0),
    currency         char(3) not null,
    distance_km      numeric(8,1) check (distance_km > 0),        -- MILEAGE
    route_from       text,
    route_to         text,
    receipt_id       uuid,
    status           text not null check (status in ('PENDING', 'APPROVED', 'REJECTED', 'RETURNED', 'CANCELLED')),
    workflow_id      uuid references workflow_instance(id),
    returned_comment text,
    created_at       timestamptz not null default now(),
    decided_at       timestamptz,
    paid_at          timestamptz,                                 -- HR marked it paid (payroll or transfer)
    paid_by          uuid,                                        -- app_user
    payment_ref      text,
    version          bigint not null default 0,
    check (paid_at is null or status = 'APPROVED')
);
create index claim_employee on claim (tenant_id, employee_id, expense_date);
create index claim_month on claim (tenant_id, claim_type_id, expense_date) where status in ('PENDING', 'APPROVED');
create index claim_unpaid on claim (tenant_id) where status = 'APPROVED' and paid_at is null;

do $$
declare t text;
begin
    foreach t in array array['claim_type', 'claim']
    loop
        execute format('alter table %I enable row level security', t);
        execute format('alter table %I force row level security', t);
        execute format(
            'create policy tenant_isolation on %I using (tenant_id::text = current_setting(''app.tenant_id'', true)) '
            'with check (tenant_id::text = current_setting(''app.tenant_id'', true))', t);
    end loop;
end $$;

-- Approval workflows can now be configured for claims too (quantity = claim amount).
alter table approval_flow drop constraint approval_flow_subject_type_check;
alter table approval_flow add constraint approval_flow_subject_type_check check (subject_type in ('LEAVE', 'CLAIM'));
