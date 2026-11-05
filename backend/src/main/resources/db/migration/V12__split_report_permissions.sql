-- "See reports" is split into "See leave reports" and "See claims reports".
-- Every role that could see reports keeps both, at the same scope; admins can then take one away.
insert into role_permission (role_id, permission, data_scope)
select role_id, p.permission, data_scope
from role_permission, (values ('reports:leave'), ('reports:claims')) as p(permission)
where role_permission.permission = 'reports:view'
on conflict (role_id, permission) do nothing;

delete from role_permission where permission = 'reports:view';
