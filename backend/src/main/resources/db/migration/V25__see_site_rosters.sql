-- "See site rosters" (attendance:rosters): the published roster of the sites you're at (names and shift times only).
-- Everyone who can clock in gets it; admins can take it away.
insert into role_permission (role_id, permission, data_scope)
select role_id, 'attendance:rosters', 'SELF'
from role_permission where permission = 'attendance:clock'
on conflict (role_id, permission) do nothing;
