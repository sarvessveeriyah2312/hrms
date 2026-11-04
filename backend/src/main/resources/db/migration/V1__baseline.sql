-- Baseline schema for the MVP core. See PRD section 9 (Data model).
-- Global identity tables (tenant, app_user) are NOT under row-level security because login
-- must resolve a user before a tenant is known; the application filters them by tenant.
-- Every tenant-owned business table has tenant_id + an RLS policy on app.tenant_id
-- (set per connection by TenantAwareDataSource).

create extension if not exists pgcrypto;
create extension if not exists citext;

-- ---------- platform ----------
create table tenant (
    id            uuid primary key default gen_random_uuid(),
    name          text not null,
    country       char(2) not null,
    plan          text not null default 'STANDARD',
    status        text not null default 'ACTIVE',
    data_region   text not null default 'ap-southeast',
    created_at    timestamptz not null default now()
);

create table legal_entity (
    id              uuid primary key default gen_random_uuid(),
    tenant_id       uuid not null references tenant(id),
    name            text not null,
    country         char(2) not null,
    registration_no text,
    currency        char(3) not null,
    time_zone       text not null default 'Asia/Kuala_Lumpur',
    created_at      timestamptz not null default now(),
    version         bigint not null default 0
);

create table audit_event (
    id          uuid primary key default gen_random_uuid(),
    tenant_id   uuid not null references tenant(id),
    actor_id    uuid,
    action      text not null,
    entity_type text not null,
    entity_id   uuid,
    before      jsonb,
    after       jsonb,
    ip          inet,
    at          timestamptz not null default now()
);

-- ---------- identity ----------
create table app_user (
    id            uuid primary key default gen_random_uuid(),
    tenant_id     uuid not null references tenant(id),
    email         citext,
    phone         text,
    password_hash text,               -- argon2id; null for SSO-only users
    status        text not null default 'ACTIVE',
    mfa_enabled   boolean not null default false,
    created_at    timestamptz not null default now(),
    version       bigint not null default 0,
    unique (tenant_id, email)
);

create table role (
    id         uuid primary key default gen_random_uuid(),
    tenant_id  uuid not null references tenant(id),
    code       text not null,
    name       text not null,
    unique (tenant_id, code)
);

create table role_permission (
    role_id    uuid not null references role(id) on delete cascade,
    permission text not null,          -- e.g. 'leave:approve'
    data_scope text not null,          -- SELF | TEAM | DEPARTMENT | ENTITY | TENANT
    primary key (role_id, permission)
);

create table user_role (
    user_id uuid not null references app_user(id) on delete cascade,
    role_id uuid not null references role(id) on delete cascade,
    primary key (user_id, role_id)
);

create table device (
    id           uuid primary key default gen_random_uuid(),
    tenant_id    uuid not null references tenant(id),
    user_id      uuid not null references app_user(id),
    platform     text not null,        -- IOS | ANDROID | WEB | KIOSK
    model        text,
    push_token   text,
    trusted      boolean not null default false,
    last_seen_at timestamptz
);

create table refresh_token (
    id          uuid primary key default gen_random_uuid(),
    user_id     uuid not null references app_user(id),
    device_id   uuid references device(id),
    token_hash  text not null unique,
    expires_at  timestamptz not null,
    revoked_at  timestamptz
);

-- ---------- org ----------
create table department (
    id         uuid primary key default gen_random_uuid(),
    tenant_id  uuid not null references tenant(id),
    entity_id  uuid not null references legal_entity(id),
    parent_id  uuid references department(id),
    name       text not null
);

create table site (
    id          uuid primary key default gen_random_uuid(),
    tenant_id   uuid not null references tenant(id),
    entity_id   uuid not null references legal_entity(id),
    name        text not null,
    lat         numeric(9,6) not null,
    lng         numeric(9,6) not null,
    radius_m    integer not null default 100 check (radius_m between 25 and 1000),
    wifi_bssids text[] not null default '{}',
    wifi_mode   text not null default 'GPS_OR_WIFI'   -- GPS_OR_WIFI | GPS_AND_WIFI
);

-- ---------- employee ----------
create table employee (
    id            uuid primary key default gen_random_uuid(),
    tenant_id     uuid not null references tenant(id),
    user_id       uuid unique references app_user(id),
    entity_id     uuid not null references legal_entity(id),
    department_id uuid references department(id),
    manager_id    uuid references employee(id),
    employee_no   text not null,
    full_name     text not null,
    join_date     date not null,
    status        text not null default 'ACTIVE',
    national_id_enc bytea,              -- encrypted with a KMS data key; never stored in clear
    version       bigint not null default 0,
    unique (tenant_id, employee_no)
);

-- ---------- attendance ----------
create table punch (
    id             uuid primary key,
    tenant_id      uuid not null references tenant(id),
    employee_id    uuid not null references employee(id),
    type           text not null check (type in ('IN','OUT')),
    server_time    timestamptz not null,
    client_time    timestamptz,
    lat            numeric(9,6),
    lng            numeric(9,6),
    accuracy_m     integer,
    site_id        uuid references site(id),
    wifi_bssid     text,
    selfie_file_id uuid,
    flags          text[] not null default '{}',
    result         text not null check (result in ('ACCEPTED','FLAGGED','BLOCKED')),
    source         text not null check (source in ('MOBILE','KIOSK','WEB','OFFLINE')),
    remarks        text,
    version        bigint not null default 0
);
create index punch_employee_time on punch (tenant_id, employee_id, server_time desc);

-- ---------- workflow ----------
create table workflow_instance (
    id            uuid primary key default gen_random_uuid(),
    tenant_id     uuid not null references tenant(id),
    subject_type  text not null,        -- LEAVE | CLAIM | CORRECTION | OVERTIME | PROFILE_CHANGE
    subject_id    uuid not null,
    state         text not null,        -- DRAFT | PENDING | APPROVED | REJECTED | RETURNED | CANCELLED
    current_level smallint,
    created_at    timestamptz not null default now(),
    version       bigint not null default 0
);

create table workflow_step (
    id           uuid primary key default gen_random_uuid(),
    tenant_id    uuid not null references tenant(id),
    instance_id  uuid not null references workflow_instance(id) on delete cascade,
    level        smallint not null,     -- 1..3, 9 = HR
    assignee_id  uuid not null references employee(id),
    due_at       timestamptz,
    acted_by     uuid references employee(id),
    action       text,                  -- APPROVE | REJECT | RETURN | OVERRIDE
    comment      text,
    acted_at     timestamptz
);

-- ---------- notification / outbox ----------
create table outbox_event (
    id           uuid primary key default gen_random_uuid(),
    tenant_id    uuid not null references tenant(id),
    event_type   text not null,
    payload      jsonb not null,
    created_at   timestamptz not null default now(),
    published_at timestamptz
);

-- ---------- row-level security on tenant-owned business tables ----------
do $$
declare t text;
begin
    foreach t in array array['legal_entity','audit_event','role','device','department','site','employee',
                             'punch','workflow_instance','workflow_step','outbox_event']
    loop
        execute format('alter table %I enable row level security', t);
        execute format('alter table %I force row level security', t);
        execute format(
            'create policy tenant_isolation on %I using (tenant_id::text = current_setting(''app.tenant_id'', true)) '
            'with check (tenant_id::text = current_setting(''app.tenant_id'', true))', t);
    end loop;
end $$;
