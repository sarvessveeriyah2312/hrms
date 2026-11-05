-- A company's own SMTP server for the emails the app sends (password resets, notices). With none, the
-- platform's server is used (MAIL_HOST), and with neither, emails are written to the API log.
create table tenant_mail_settings (
    tenant_id        uuid primary key references tenant(id),
    enabled          boolean not null default true,
    host             text not null,
    port             integer not null check (port between 1 and 65535),
    security         text not null default 'STARTTLS' check (security in ('NONE', 'STARTTLS', 'SSL')),
    username         text,
    password_enc     text,            -- AES-GCM with the server's secret key; never sent back to the browser
    from_name        text,
    from_email       text not null,
    reply_to         text,
    updated_at       timestamptz not null default now(),
    updated_by       uuid,            -- app_user
    last_test_at     timestamptz,
    last_test_ok     boolean,
    last_test_error  text
);

alter table tenant_mail_settings enable row level security;
alter table tenant_mail_settings force row level security;
create policy tenant_isolation on tenant_mail_settings
    using (tenant_id::text = current_setting('app.tenant_id', true))
    with check (tenant_id::text = current_setting('app.tenant_id', true));
