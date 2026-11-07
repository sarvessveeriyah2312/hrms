-- Payroll documents (roadmap phase 4): payslips and tax forms, payroll export and the payroll dashboard.

-- ---------- the payroll module ----------
-- A new licensable module: switched on for every existing company (admins can switch it off).
-- tenant_module is under forced row-level security, so each company's row is written with that company bound.
do $$
declare t uuid;
begin
    for t in select id from tenant loop
        perform set_config('app.tenant_id', t::text, true);
        insert into tenant_module (tenant_id, module, enabled) values (t, 'payroll', true)
        on conflict (tenant_id, module) do nothing;
    end loop;
    perform set_config('app.tenant_id', '', true);
end $$;

-- Everyone who can see their own employee record can open their own payslips and tax forms.
insert into role_permission (role_id, permission, data_scope)
select role_id, 'payroll:view', 'SELF'
from role_permission where permission = 'employee:view'
on conflict (role_id, permission) do nothing;

-- Roles that edit employee records company-wide run payroll documents, the export and the dashboard.
insert into role_permission (role_id, permission, data_scope)
select rp.role_id, p.permission, 'TENANT'
from role_permission rp
cross join (values ('payroll:manage'), ('payroll:export'), ('payroll:dashboard')) as p(permission)
where rp.permission = 'employee:manage' and rp.data_scope = 'TENANT'
on conflict (role_id, permission) do nothing;

-- ---------- payslips and tax forms ----------
-- kind PAYSLIP: period is the first day of the pay month. kind TAX_FORM: period is 1 January of the tax year and
-- form_code says which form (EA in Malaysia, IR8A in Singapore, IR56B in Hong Kong, FORM16 in India).
-- Uploaded (a PDF per person) or generated from pay lines (payroll_line). Drafts are seen only by HR until published.
-- Read receipts: when the employee first and last opened it, and how often.
create table payroll_document (
    id              uuid primary key,
    tenant_id       uuid not null references tenant(id),
    employee_id     uuid not null references employee(id),
    kind            text not null check (kind in ('PAYSLIP', 'TAX_FORM')),
    period          date not null,
    form_code       text check (form_code in ('EA', 'IR8A', 'IR56B', 'FORM16', 'OTHER')),
    file_id         uuid not null references file_object(id),
    source          text not null check (source in ('UPLOAD', 'GENERATED')),
    currency        char(3),
    gross           numeric(14, 2),
    deductions      numeric(14, 2),
    net             numeric(14, 2),
    employer_cost   numeric(14, 2),         -- employer contributions on top of gross (EPF, CPF, MPF…)
    status          text not null default 'DRAFT' check (status in ('DRAFT', 'PUBLISHED')),
    published_at    timestamptz,
    published_by    uuid references app_user(id),
    first_viewed_at timestamptz,
    last_viewed_at  timestamptz,
    view_count      integer not null default 0,
    created_by      uuid references app_user(id),
    created_at      timestamptz not null default now(),
    version         bigint not null default 0,
    check (extract(day from period) = 1),
    check (kind = 'PAYSLIP' or (form_code is not null and extract(month from period) = 1)),
    check (kind = 'TAX_FORM' or form_code is null),
    check (status = 'DRAFT' or published_at is not null)
);
create unique index payroll_document_one on payroll_document (employee_id, kind, period, coalesce(form_code, ''));
create index payroll_document_period on payroll_document (tenant_id, kind, period, status);

-- The lines of a generated payslip: earnings, deductions and employer contributions, in order.
create table payroll_line (
    id          uuid primary key default gen_random_uuid(),
    tenant_id   uuid not null references tenant(id),
    document_id uuid not null references payroll_document(id) on delete cascade,
    kind        text not null check (kind in ('EARNING', 'DEDUCTION', 'EMPLOYER')),
    label       text not null,
    amount      numeric(14, 2) not null,
    position    integer not null
);
create index payroll_line_doc on payroll_line (document_id, position);

do $$
declare t text;
begin
    foreach t in array array['payroll_document', 'payroll_line'] loop
        execute format('alter table %I enable row level security', t);
        execute format('alter table %I force row level security', t);
        execute format($p$create policy tenant_isolation on %I
            using (tenant_id::text = current_setting('app.tenant_id', true))
            with check (tenant_id::text = current_setting('app.tenant_id', true))$p$, t);
    end loop;
end $$;
