-- Work sites for attendance: where people may clock in, and how.
--   Location checks: inside the geofence (GEOFENCE) and/or on the site's office Wi-Fi (WIFI). With both on,
--   wifi_mode says whether either one is enough (GPS_OR_WIFI) or both are needed (GPS_AND_WIFI).
--   Extra requirements: a selfie (SELFIE) or a face match (FACE); clocking on the site's shared tablet (KIOSK).

alter table site add column address text;
alter table site add column active boolean not null default true;
alter table site add column methods text[] not null default '{GEOFENCE,WIFI}';
alter table site add column remarks_required boolean not null default false;
alter table site add column created_at timestamptz not null default now();
alter table site add column updated_at timestamptz not null default now();
alter table site add constraint site_methods_known
    check (methods <@ array['GEOFENCE', 'WIFI', 'SELFIE', 'FACE', 'KIOSK']::text[]);
alter table site add constraint site_wifi_mode_known check (wifi_mode in ('GPS_OR_WIFI', 'GPS_AND_WIFI'));
create unique index site_name_unique on site (tenant_id, lower(name));

-- Office Wi-Fi networks, by access point (BSSID), with the network name and a note for admins.
create table site_wifi (
    id         uuid primary key default gen_random_uuid(),
    tenant_id  uuid not null references tenant(id),
    site_id    uuid not null references site(id) on delete cascade,
    bssid      text not null check (bssid ~ '^[0-9a-f]{2}(:[0-9a-f]{2}){5}$'),
    ssid       text,
    label      text,
    unique (site_id, bssid)
);
insert into site_wifi (tenant_id, site_id, bssid)
select s.tenant_id, s.id, lower(b) from site s, unnest(s.wifi_bssids) as b
where lower(b) ~ '^[0-9a-f]{2}(:[0-9a-f]{2}){5}$'
on conflict do nothing;
alter table site drop column wifi_bssids;

-- Who may clock in at a site. A person can belong to several sites.
create table employee_site (
    tenant_id   uuid not null references tenant(id),
    employee_id uuid not null references employee(id) on delete cascade,
    site_id     uuid not null references site(id) on delete cascade,
    added_at    timestamptz not null default now(),
    primary key (employee_id, site_id)
);
create index employee_site_site on employee_site (site_id);

do $$
declare t text;
begin
    foreach t in array array['site_wifi', 'employee_site']
    loop
        execute format('alter table %I enable row level security', t);
        execute format('alter table %I force row level security', t);
        execute format(
            'create policy tenant_isolation on %I using (tenant_id::text = current_setting(''app.tenant_id'', true)) '
            'with check (tenant_id::text = current_setting(''app.tenant_id'', true))', t);
    end loop;
end $$;
