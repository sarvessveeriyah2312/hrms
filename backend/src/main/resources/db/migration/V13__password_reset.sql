-- Forgot password (one-time email links) and change password.

-- After an admin sets a temporary password (new user or reset), the person must choose their own at sign-in.
alter table app_user add column must_change_password boolean not null default false;
alter table app_user add column password_changed_at timestamptz;

-- Reset links. Only a hash of the token is stored. Global like app_user (no RLS): it's used before sign-in,
-- and the token itself is the proof.
create table password_reset (
    id          uuid primary key default gen_random_uuid(),
    user_id     uuid not null references app_user(id) on delete cascade,
    token_hash  text not null unique,
    created_at  timestamptz not null default now(),
    expires_at  timestamptz not null,
    used_at     timestamptz
);
create index password_reset_user on password_reset (user_id, created_at desc);
