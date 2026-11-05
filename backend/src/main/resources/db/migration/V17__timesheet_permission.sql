-- "See timesheets" (attendance:timesheets) is split out of "See attendance" (attendance:view), which now covers Today.
-- Every role that could see attendance keeps timesheets at the same scope; admins can then take one away.
insert into role_permission (role_id, permission, data_scope)
select role_id, 'attendance:timesheets', data_scope
from role_permission
where permission = 'attendance:view'
on conflict (role_id, permission) do nothing;
