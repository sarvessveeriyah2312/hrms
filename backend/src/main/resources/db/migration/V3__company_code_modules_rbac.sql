-- Company code at login, module licences per company, and richer roles.

-- ---------- company code ----------
-- Typed (or scanned) at login to pick the company when one email belongs to several.
alter table tenant add column code citext;
update tenant
   set code = upper(substr(regexp_replace(name, '[^A-Za-z0-9]', '', 'g'), 1, 12)) || '-' || substr(id::text, 1, 4)
 where code is null;
alter table tenant alter column code set not null;
alter table tenant add constraint tenant_code_unique unique (code);
alter table tenant add constraint tenant_code_format check (code ~ '^[A-Za-z0-9-]{3,20}$');

-- ---------- module licences ----------
-- One row per licensable module the company has. A missing or disabled row means the module is off:
-- its permissions are dropped from the access token and its menus are hidden.
create table tenant_module (
    tenant_id  uuid not null references tenant(id),
    module     text not null,            -- attendance | leave | claims | reports
    enabled    boolean not null default true,
    updated_at timestamptz not null default now(),
    primary key (tenant_id, module)
);

-- ---------- roles ----------
alter table role add column description text;
alter table role add column system boolean not null default false;  -- built-in: can't be deleted
alter table role_permission add constraint role_permission_scope
    check (data_scope in ('SELF', 'TEAM', 'DEPARTMENT', 'ENTITY', 'TENANT'));

-- ---------- approvers ----------
-- employee.manager_id is the first-level approver; the backup approver acts when the manager is away.
alter table employee add column backup_approver_id uuid references employee(id);
alter table employee add constraint employee_backup_not_self check (backup_approver_id is null or backup_approver_id <> id);
alter table employee add constraint employee_manager_not_self check (manager_id is null or manager_id <> id);

alter table app_user add column last_login_at timestamptz;

-- ---------- RLS on the new tenant-owned table ----------
alter table tenant_module enable row level security;
alter table tenant_module force row level security;
create policy tenant_isolation on tenant_module
    using (tenant_id::text = current_setting('app.tenant_id', true))
    with check (tenant_id::text = current_setting('app.tenant_id', true));
