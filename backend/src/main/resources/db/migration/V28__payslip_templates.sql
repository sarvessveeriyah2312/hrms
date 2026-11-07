-- Payslip templates: HR picks the layout generated payslips use (a company default, or one per generation).

-- Which template drew a generated payslip.
alter table payroll_document add column template text;

-- Pay lines carry what the richer layouts show: earnings grouped as basic pay, allowances or overtime, and for
-- overtime (or basic pay) the hours or days and the rate.
alter table payroll_line add column category text check (category in ('BASIC', 'ALLOWANCE', 'OVERTIME'));
alter table payroll_line add column quantity numeric(10, 2);
alter table payroll_line add column rate numeric(12, 4);
update payroll_line set category = 'ALLOWANCE' where kind = 'EARNING' and category is null;

-- Company payroll settings (one row per company; missing means the defaults).
create table payroll_settings (
    tenant_id        uuid primary key references tenant(id),
    payslip_template text not null default 'CLASSIC',
    updated_at       timestamptz not null default now(),
    updated_by       uuid references app_user(id)
);
alter table payroll_settings enable row level security;
alter table payroll_settings force row level security;
create policy tenant_isolation on payroll_settings
    using (tenant_id::text = current_setting('app.tenant_id', true))
    with check (tenant_id::text = current_setting('app.tenant_id', true));
