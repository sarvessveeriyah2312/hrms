-- Leave: carry-over expiry, return for changes, HR on behalf, certificates, working week. Plus a files store.

-- ---------- workflow: a request can be sent back to the requester ----------
alter table workflow_instance drop constraint workflow_instance_state;
alter table workflow_instance add constraint workflow_instance_state
    check (state in ('PENDING', 'APPROVED', 'REJECTED', 'RETURNED', 'CANCELLED'));

-- ---------- leave types ----------
-- Carried days must be used by the end of this month of the new year (null: they never expire).
alter table leave_type add column carry_expiry_month smallint check (carry_expiry_month between 1 and 12);
-- A supporting document (e.g. medical certificate) must be attached when applying.
alter table leave_type add column requires_attachment boolean not null default false;

-- ---------- leave requests ----------
alter table leave_request drop constraint leave_request_status_check;
alter table leave_request add constraint leave_request_status_check
    check (status in ('PENDING', 'APPROVED', 'REJECTED', 'RETURNED', 'CANCELLED'));
alter table leave_request add column attachment_id uuid;
alter table leave_request add column recorded_by uuid;          -- app_user who recorded it for the employee (HR)
alter table leave_request add column returned_comment text;     -- why it was sent back
alter table leave_request add column cancel_reason text;        -- when HR cancels approved leave
alter table leave_request add column cancelled_by uuid;

-- ---------- working week per legal entity (ISO days: 1 = Monday ... 7 = Sunday) ----------
alter table legal_entity add column work_days smallint[] not null default '{1,2,3,4,5}';

-- ---------- files ----------
-- Stored in the database for now; the FileStore interface lets S3/MinIO replace it without changing callers.
create table file_object (
    id            uuid primary key,
    tenant_id     uuid not null references tenant(id),
    owner_user_id uuid not null,
    name          text not null,
    content_type  text not null,
    size_bytes    integer not null check (size_bytes > 0),
    sha256        text not null,
    data          bytea not null,
    created_at    timestamptz not null default now()
);
alter table file_object enable row level security;
alter table file_object force row level security;
create policy tenant_isolation on file_object
    using (tenant_id::text = current_setting('app.tenant_id', true))
    with check (tenant_id::text = current_setting('app.tenant_id', true));
